package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectKey;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Operator boundary over the retained resolver. No target invocation, workflow resume or schema installation. */
@ApplicationScoped
@Startup
public class ManualEffectOperations {
    private static final Set<String> SCOPES = Set.of("oa.recover");
    private static final String KEY_HASH =
            "effect.audit_fingerprint('oa.effect.audit-key.v1',s.thread_name,s.node_id,s.idempotency_key)";
    private static final String FROM = " from effect.audit_scope s join effect.ledger l "
            + "using(thread_name,node_id,idempotency_key) join task.instance i "
            + "on i.tenant=s.tenant and i.id=s.registry_instance_id ";
    private static final String WHERE = " where s.tenant=? and s.registry_instance_id=?";
    public record UnknownEffect(String effectId, Instant firstSeen, Instant lastSeen) { }
    public record Page(List<UnknownEffect> items, String nextCursor) {
        public Page { items = List.copyOf(items); }
    }
    public record Resolution(String effectId, String requestId, EffectState state) { }

    private final boolean enabled;
    private final DataSource dataSource;
    private final TransactionManager transactions;
    private final Pep pep;
    private final ManualEffectResolver resolver;

    @Inject
    public ManualEffectOperations(DataSource dataSource, TransactionManager transactions, Pep pep,
            @ConfigProperty(name="agentic.effects.manual-resolution.enabled", defaultValue="false") boolean enabled) {
        this.enabled = enabled; this.dataSource = Objects.requireNonNull(dataSource);
        this.transactions = Objects.requireNonNull(transactions); this.pep = Objects.requireNonNull(pep);
        var ledger = new JdbcEffectLedger(dataSource, transactions, new EffectAuditProducer());
        this.resolver = new ManualEffectResolver(dataSource, transactions, pep, new AuditAppendOnce(dataSource), ledger);
    }

    /** An enabled deployment must install the reviewed V80 store separately; never migrate unrelated proposals. */
    @PostConstruct
    void requireInstalledSchema() {
        if (!enabled) return;
        read(c -> {
            try (var q = c.prepareStatement("select decision_id,request_id,source_key,desired_state "
                    + "from effect.manual_decision where false")) { q.executeQuery().close(); }
            try (var q = c.prepareStatement("select decision_id,effect_event_id,resulting_state "
                    + "from effect.manual_decision_applied where false")) { q.executeQuery().close(); }
            return null;
        });
    }

    public void requireEnabled() {
        if (!enabled) throw new ProblemException(Response.Status.NOT_FOUND, "No such operation.");
    }

    public Page unknown(CallerAuthority authority, Ulid instance, PageRequest page) {
        requireEnabled(); authorize(authority, instance);
        String after = page.cursor() == null ? "" : digest(page.cursor());
        Page result = read(c -> {
            requireInstance(c, authority.caller().tenant(), instance);
            var found = new ArrayList<UnknownEffect>();
            String sql = "select " + KEY_HASH + ",m.first_seen,m.last_seen" + FROM
                    + "join effect.manual_resolution m on m.thread_name=s.thread_name and m.node_id=s.node_id "
                    + "and m.idempotency_key=s.idempotency_key" + WHERE
                    + " and l.state='INTENDED' and l.attempted and l.request_fingerprint is not null"
                    + " and " + KEY_HASH + ">? and exists(select 1 from effect.event e where "
                    + "e.thread_name=s.thread_name and e.node_id=s.node_id and e.idempotency_key=s.idempotency_key "
                    + "and e.reason='effect.outcome.unknown' and e.from_state='INTENDED' and e.to_state='INTENDED')"
                    + " and m.closed_at is null order by 1 limit ?";
            try (var q = c.prepareStatement(sql)) {
                q.setQueryTimeout(5); q.setString(1, authority.caller().tenant()); q.setString(2, instance.value());
                q.setString(3, after); q.setInt(4, page.limit() + 1);
                try (var r = q.executeQuery()) {
                    while (r.next()) found.add(new UnknownEffect("efp1-" + r.getString(1),
                            r.getTimestamp(2).toInstant(), r.getTimestamp(3).toInstant()));
                }
            }
            boolean more = found.size() > page.limit();
            if (more) found.remove(found.size() - 1);
            return new Page(found, more ? found.get(found.size() - 1).effectId() : null);
        });
        authorize(authority, instance); // Storage and policy audit can block; withhold stale output.
        return result;
    }

    public Resolution resolve(CallerAuthority authority, Ulid instance, String effectId, Ulid requestId,
            EffectState outcome, String reason) {
        requireEnabled(); authorize(authority, instance);
        String hash = digest(effectId);
        if (outcome != EffectState.CONFIRMED && outcome != EffectState.FAILED)
            throw new IllegalArgumentException("Outcome must be CONFIRMED or FAILED.");
        if (!("OPERATOR_VERIFIED".equals(reason) || (outcome == EffectState.CONFIRMED && "TARGET_CONFIRMED".equals(reason))
                || (outcome == EffectState.FAILED && "TARGET_REJECTED".equals(reason))))
            throw new IllegalArgumentException("Reason does not match the requested outcome.");
        EffectKey key = read(c -> {
            requireInstance(c, authority.caller().tenant(), instance);
            try (var q = c.prepareStatement("select s.thread_name,s.node_id,s.idempotency_key" + FROM + WHERE
                    + " and " + KEY_HASH + "=? limit 2")) {
                q.setQueryTimeout(5); q.setString(1, authority.caller().tenant()); q.setString(2, instance.value());
                q.setString(3, hash);
                try (var r = q.executeQuery()) {
                    if (!r.next()) throw new ProblemException(Response.Status.NOT_FOUND, "No such effect.");
                    var selected = new EffectKey(ThreadId.parse(r.getString(1)), r.getString(2), r.getString(3));
                    if (r.next()) throw new IllegalStateException("effect reference is ambiguous");
                    return selected;
                }
            }
        });
        authorize(authority, instance);
        var effect = resolver.resolve(authority, key, requestId, outcome, reason);
        var result = new Resolution(effectId, requestId.value(), effect.state());
        authorize(authority, instance); // A committed resolution remains committed if final authority is lost.
        return result;
    }

    private void authorize(CallerAuthority authority, Ulid instance) {
        authority.requireCurrent(SCOPES);
        if (authority.delegated() || authority.caller().subject().kind() != SubjectKind.USER)
            throw new ProblemException(Response.Status.FORBIDDEN, "A verified human operator is required.");
        var decision = pep.instanceOperation(Pep.InstanceOperation.RESOLVE_EFFECT,
                authority.caller().subject(), authority.caller().scopes(), instance);
        if (decision.outcome() != DecisionOutcome.ALLOW) throw new PolicyDenied(decision);
        authority.requireCurrent(SCOPES);
    }

    private static void requireInstance(Connection c, String tenant, Ulid instance) throws SQLException {
        try (var q = c.prepareStatement("select 1 from task.instance where tenant=? and id=?")) {
            q.setQueryTimeout(5); q.setString(1, tenant); q.setString(2, instance.value());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new ProblemException(Response.Status.NOT_FOUND, "No such instance.");
            }
        }
    }

    private static String digest(String reference) {
        if (reference == null || !reference.matches("efp1-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid effect reference or cursor.");
        return reference.substring(5);
    }

    private <T> T read(Transactions.Work<T> work) {
        return Transactions.inSuspendedTransaction(transactions, () -> Transactions.inTransaction(dataSource, c -> {
            try (var q = c.createStatement()) { q.execute("set local statement_timeout='5s'"); }
            return work.run(c);
        }, 5_000));
    }
}
