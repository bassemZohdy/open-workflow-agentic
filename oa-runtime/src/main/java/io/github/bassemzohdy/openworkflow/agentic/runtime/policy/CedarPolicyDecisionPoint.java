package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.CedarList;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Policy decision point backed by Cedar, embedded in the runtime (ADR 0021, WP-006 D-2). It evaluates every request
 * against the last good {@link PolicySnapshot}, and nothing else: there is no cache, so a replaced or revoked policy
 * takes effect on the next decision.
 *
 * <p><b>Request mapping.</b> The principal is the subject acting, typed by its kind ({@code Agent}, {@code User},
 * {@code Workflow}, {@code Service}). The action is the kind of action in lower case ({@code tool_call}). The resource
 * is {@code Resource::"<resource string>"}. The context carries the {@link PolicyContext} facts, with a fixed set of
 * names: {@code scopes}, {@code riskTier}, {@code budgetRemainingMicros} (-1 when unknown), {@code onBehalfOf},
 * {@code onBehalfOfKind}, {@code delegationGrantId}, {@code permittedByContract} (false when unknown) and
 * {@code taskId}. Unknown text is the empty string.
 *
 * <p><b>Outcomes.</b> ALLOW only when a permit matched, no forbid matched and no policy raised an error. A forbid
 * that matched gives DENY attributed to that forbid. No policy matched gives DENY attributed to
 * {@link #DEFAULT_DENY_POLICY}. Any evaluation error, a missing snapshot, an engine failure or an unmapped engine
 * name gives ERROR, even when another policy would have allowed the request. Every decision names the stable policy
 * id and the snapshot version.
 */
public final class CedarPolicyDecisionPoint implements PolicyDecisionPoint {

    /** Name of the component in decisions produced here. */
    public static final String DECIDED_BY = "cedar-embedded";
    /** Policy id recorded when no policy in the snapshot matched. */
    public static final String DEFAULT_DENY_POLICY = "oa.default-deny";

    private static final int MAX_REASON = 900;
    private static final EntityTypeName PRINCIPAL_AGENT = type("Agent");
    private static final EntityTypeName PRINCIPAL_USER = type("User");
    private static final EntityTypeName PRINCIPAL_WORKFLOW = type("Workflow");
    private static final EntityTypeName PRINCIPAL_SERVICE = type("Service");
    private static final EntityTypeName ACTION = type("Action");
    private static final EntityTypeName RESOURCE = type("Resource");

    /** The outcome of an attempt to change the active snapshot. */
    public sealed interface RefreshOutcome permits RefreshOutcome.Accepted, RefreshOutcome.Rejected {

        /** The new snapshot is now effective. */
        record Accepted(String versionId, String contentHash) implements RefreshOutcome {
        }

        /** The previous snapshot stays effective; the reason says why the new one was refused. */
        record Rejected(String reason) implements RefreshOutcome {
        }
    }

    private record ActiveSnapshot(PolicySnapshot snapshot, CedarPolicySet.Compiled compiled) {
    }

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private final Clock clock;
    private final AtomicReference<ActiveSnapshot> active = new AtomicReference<>();

    public CedarPolicyDecisionPoint(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CedarPolicyDecisionPoint() {
        this(Clock.systemUTC());
    }

    /**
     * Makes a snapshot effective, if and only if it is valid: its texts hash to its declared hash, every policy
     * parses, and every policy has a unique {@code @id}. Otherwise throws {@link IllegalArgumentException} and the
     * previous snapshot stays effective.
     */
    public synchronized void install(PublishedPolicySnapshot published) {
        PolicySnapshot snapshot = new PolicySnapshot(published.versionId(), published.declaredContentHash(),
                published.policyTexts());
        CedarPolicySet.Compiled compiled = CedarPolicySet.compile(snapshot.policyTexts());
        active.set(new ActiveSnapshot(snapshot, compiled));
    }

    /**
     * Reads the source and installs what it publishes. Never throws: an unreadable source, or a snapshot that is
     * refused, is reported as {@link RefreshOutcome.Rejected} and the previous snapshot stays effective.
     */
    public synchronized RefreshOutcome refresh(PolicySnapshotSource source) {
        PublishedPolicySnapshot published;
        try {
            published = source.read();
        } catch (IOException | RuntimeException e) {
            return new RefreshOutcome.Rejected(bounded("snapshot source unavailable: " + e.getClass().getSimpleName()));
        }
        if (published == null) {
            return new RefreshOutcome.Rejected("snapshot source returned nothing");
        }
        try {
            install(published);
        } catch (IllegalArgumentException e) {
            return new RefreshOutcome.Rejected(bounded("snapshot rejected: " + e.getMessage()));
        } catch (RuntimeException | LinkageError e) {
            return new RefreshOutcome.Rejected(bounded("snapshot rejected: " + e.getClass().getSimpleName()));
        }
        PolicySnapshot now = active.get().snapshot();
        return new RefreshOutcome.Accepted(now.versionId(), now.contentHash());
    }

    /** The snapshot that decisions are made against, or empty when none has ever been accepted. */
    public Optional<PolicySnapshot> activeSnapshot() {
        ActiveSnapshot current = active.get();
        return current == null ? Optional.empty() : Optional.of(current.snapshot());
    }

    @Override
    public Decision decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        ActiveSnapshot current = active.get();
        if (current == null) {
            return error(now, "no approved policy snapshot is loaded", null);
        }
        String version = current.snapshot().versionId();
        try {
            AuthorizationResponse response = engine.isAuthorized(toCedar(request), current.compiled().policies(),
                    new Entities(entities(request)));
            AuthorizationSuccessResponse success = response.success.orElse(null);
            if (success == null) {
                return error(now, "policy engine returned no decision", version);
            }
            Map<String, String> stable = current.compiled().stableIds();
            if (!success.getErrors().isEmpty()) {
                return error(now, "policy evaluation error; the request is refused", version);
            }
            TreeSet<String> deciding = new TreeSet<>();
            for (String engineName : success.getReason()) {
                String id = stable.get(engineName);
                if (id == null) {
                    return error(now, "engine named a policy that is not in the snapshot", version);
                }
                deciding.add(id);
            }
            if (success.isAllowed()) {
                if (deciding.isEmpty()) {
                    return error(now, "allow without a permit policy", version);
                }
                return decision(now, DecisionOutcome.ALLOW, "allowed by " + deciding, deciding.first(), version);
            }
            if (deciding.isEmpty()) {
                return decision(now, DecisionOutcome.DENY, "no permit matched: default deny", DEFAULT_DENY_POLICY,
                        version);
            }
            return decision(now, DecisionOutcome.DENY, "denied by " + deciding, deciding.first(), version);
        } catch (Exception | LinkageError e) {
            // LinkageError: the native Cedar library failed to load or link. Still a refusal, never an allow.
            return error(now, "policy engine failure (" + e.getClass().getSimpleName() + ")", version);
        }
    }

    private Decision decision(Instant now, DecisionOutcome outcome, String reason, String policyId, String version) {
        return new Decision(DECIDED_BY, outcome, bounded(reason), new PolicyRef(policyId, version), now);
    }

    /**
     * ERROR, attributed to the fail-closed rule and to the snapshot it was evaluated against (or to the built-in
     * version when no snapshot is loaded), so no caller can read it as an allow.
     */
    private Decision error(Instant now, String reason, String version) {
        PolicyRef policy = version == null ? PolicyRef.FAIL_CLOSED
                : new PolicyRef(PolicyRef.FAIL_CLOSED.policyId(), version);
        return new Decision(DECIDED_BY, DecisionOutcome.ERROR,
                bounded(reason + " (snapshot " + (version == null ? "none" : version) + ")"), policy, now);
    }

    private static String bounded(String text) {
        return text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON);
    }

    static com.cedarpolicy.model.AuthorizationRequest toCedar(AuthorizationRequest request) {
        PolicyContext context = request.context();
        Subject subject = request.subject();
        EntityUID principal = principalType(subject.kind()).of(subject.id());
        EntityUID action = ACTION.of(request.action().name().toLowerCase(Locale.ROOT));
        EntityUID resource = RESOURCE.of(request.resource());
        Subject onBehalfOf = context.onBehalfOf();
        Map<String, Value> values = Map.of(
                "scopes", new CedarList(context.scopes().stream().sorted().<Value>map(PrimString::new).toList()),
                "riskTier", new PrimString(context.riskTier() == null ? "" : context.riskTier().name()),
                "budgetRemainingMicros", new PrimLong(
                        context.budgetRemainingMicros() == null ? -1L : context.budgetRemainingMicros()),
                "onBehalfOf", new PrimString(onBehalfOf == null ? "" : onBehalfOf.id()),
                "onBehalfOfKind", new PrimString(onBehalfOf == null ? "" : onBehalfOf.kind().name()),
                "delegationGrantId", new PrimString(
                        context.delegationGrantId() == null ? "" : context.delegationGrantId().value()),
                "permittedByContract", new PrimBool(Boolean.TRUE.equals(context.permittedByContract())),
                "taskId", new PrimString(request.taskId() == null ? "" : request.taskId().value()));
        return new com.cedarpolicy.model.AuthorizationRequest(principal, action, resource, values);
    }

    private static Set<Entity> entities(AuthorizationRequest request) {
        EntityUID principal = principalType(request.subject().kind()).of(request.subject().id());
        EntityUID resource = RESOURCE.of(request.resource());
        return Set.of(new Entity(principal), new Entity(resource));
    }

    private static EntityTypeName principalType(SubjectKind kind) {
        return switch (kind) {
            case USER -> PRINCIPAL_USER;
            case AGENT -> PRINCIPAL_AGENT;
            case WORKFLOW -> PRINCIPAL_WORKFLOW;
            case SERVICE -> PRINCIPAL_SERVICE;
        };
    }

    private static EntityTypeName type(String name) {
        return EntityTypeName.parse(name).orElseThrow(() -> new IllegalStateException("bad type " + name));
    }
}
