package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The identity of workflow instances: binds it at start, checks it again before a restored instance continues, renews it
 * after a long wait. Everything persisted is an identifier (docs/identity.md sections 5 and 7). It is also the
 * {@link ResumeGate} of the reconciler.
 *
 * <ul>
 *   <li><b>Start.</b> {@link #bindAtStart} validates the grant (active now, same tenant, given by the user who starts the
 *       instance) and returns the work that writes the authority row in the same transaction as the instance row.</li>
 *   <li><b>Resume.</b> {@link #check} (the gate) re-checks the grant. An ended grant sets the state to
 *       REAUTHORIZATION_REQUIRED, writes the audit event {@value #REQUIRED_EVENT}, and answers REAUTHORIZATION_REQUIRED, so
 *       the step does not proceed. A check that cannot be made answers UNAVAILABLE; it never answers PROCEED.</li>
 *   <li><b>Renewal.</b> {@link #reauthorize} binds a new grant of the same user to the same agent. Only the user who
 *       started the instance may do it.</li>
 * </ul>
 * An instance with no authority row, or a row without a grant, carries no delegated authority and always proceeds.
 */
public class InstanceAuthorities implements ResumeGate {

    public static final String REQUIRED_EVENT = "identity.reauthorization.required";
    public static final String RENEWED_EVENT = "identity.reauthorization.granted";

    private static final Logger LOG = Logger.getLogger(InstanceAuthorities.class);
    private static final Subject RUNTIME = new Subject(SubjectKind.SERVICE, "agentic-runtime");

    private final InstanceAuthorityStore store;
    private final DelegationGrants grants;
    private final IdentityAudit audit;
    private final Clock clock;

    public InstanceAuthorities(InstanceAuthorityStore store, DelegationGrants grants, IdentityAudit audit, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    /**
     * Validates the grant for a new instance and returns the work to run in the instance's insert transaction.
     *
     * @param user          the verified user who starts the instance
     * @param grantId       the grant the instance runs under, or null for an instance without delegated authority
     * @param correlationId the correlation id of the start request, or null
     * @throws GrantException {@code NOT_FOUND} for an unknown grant, {@code FORBIDDEN} if it belongs to another tenant or
     *                        user or the caller is not a user, {@code INACTIVE} if it is revoked or expired,
     *                        {@code UNAVAILABLE} if it cannot be checked
     */
    public InstanceStore.InsertHook bindAtStart(String tenant, Subject user, Ulid grantId, String correlationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(user, "user");
        if (grantId != null) {
            if (user.kind() != SubjectKind.USER) {
                throw new GrantException(GrantException.Code.FORBIDDEN, "only a user starts an instance under a grant");
            }
            DelegationGrant grant = activeGrantOf(grantId, tenant, user);
            if (grant.parentId() != null) {
                // A chain's child grant belongs to an agent's work, not to an instance a user starts.
                throw new GrantException(GrantException.Code.FORBIDDEN, "an instance runs under a grant given by a user");
            }
        }
        Instant now = now();
        return (connection, instanceId) -> store.insert(connection, new InstanceAuthority(instanceId, tenant, user,
                grantId, correlationId, InstanceAuthority.State.AUTHORIZED, null, now, now));
    }

    private DelegationGrant activeGrantOf(Ulid grantId, String tenant, Subject user) {
        GrantCheck check;
        try {
            check = grants.check(grantId);
        } catch (RuntimeException e) {
            throw new GrantException(GrantException.Code.UNAVAILABLE, "the grant could not be checked", e);
        }
        if (check instanceof GrantCheck.Inactive inactive) {
            throw new GrantException(inactive.reason() == GrantCheck.Reason.UNKNOWN
                    ? GrantException.Code.NOT_FOUND : GrantException.Code.INACTIVE, "the grant is not usable");
        }
        DelegationGrant grant = ((GrantCheck.Active) check).grant();
        if (!grant.tenant().equals(tenant) || !grant.delegator().equals(user)) {
            // Same answer as for an unknown grant: whether another user's grant exists is not revealed.
            throw new GrantException(GrantException.Code.NOT_FOUND, "the grant is not usable");
        }
        return grant;
    }

    // ---- resume ----------------------------------------------------------------------------------------------------

    /** The reconciler's gate. Fails closed: an exception is never a PROCEED (the reconciler treats it as UNAVAILABLE). */
    @Override
    public Decision check(InstanceRecord row) {
        Optional<InstanceAuthority> authority = store.find(row.id());
        if (authority.isEmpty() || !authority.get().delegated()) {
            return Decision.proceed();
        }
        return recheck(authority.get());
    }

    /**
     * Re-checks the grant of an instance: used before a restored instance continues and by any step that is about to
     * use the grant after a wait.
     *
     * @return PROCEED, REAUTHORIZATION_REQUIRED (the instance is now marked and audited), or UNAVAILABLE
     */
    public Decision recheck(Ulid instanceId) {
        try {
            Optional<InstanceAuthority> authority = store.find(instanceId);
            if (authority.isEmpty() || !authority.get().delegated()) {
                return Decision.proceed();
            }
            return recheck(authority.get());
        } catch (RuntimeException e) {
            LOG.warnf("the authority of instance %s could not be checked (%s)", instanceId, e.getClass().getSimpleName());
            return new Decision(Outcome.UNAVAILABLE, "authority.unavailable");
        }
    }

    private Decision recheck(InstanceAuthority authority) {
        if (authority.state() == InstanceAuthority.State.REAUTHORIZATION_REQUIRED) {
            // Only a renewal by the user clears this. The grant coming back to life is not possible, and is not trusted.
            return new Decision(Outcome.REAUTHORIZATION_REQUIRED, authority.stateReason());
        }
        GrantCheck check = grants.check(authority.grantId());
        if (check instanceof GrantCheck.Active) {
            return Decision.proceed();
        }
        String reason = "grant." + ((GrantCheck.Inactive) check).reason().name().toLowerCase(Locale.ROOT);
        require(authority, reason);
        return new Decision(Outcome.REAUTHORIZATION_REQUIRED, reason);
    }

    /**
     * Records that the instance needs re-authorization, for example because a step's token exchange was refused with
     * GRANT_INACTIVE. The audit event is written first; if it cannot be written nothing changes and the exception
     * propagates, so the instance is not marked without a record and the step does not proceed either way.
     */
    public void require(InstanceAuthority authority, String reason) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("grantId", authority.grantId().value());
        attributes.put("reason", reason);
        audit.record(RUNTIME, REQUIRED_EVENT, IdentityAudit.INSTANCE_AUTHORITY, authority.instanceId().value(),
                authority.tenant(), authority.correlationId(), attributes);
        store.markReauthorizationRequired(authority.instanceId(), reason, now());
    }

    // ---- renewal ---------------------------------------------------------------------------------------------------

    /**
     * The user who started the instance gives it a new grant after the old one ended.
     *
     * @param actor    the verified caller; must be the user who started the instance
     * @param newGrant a grant of the same user, in the same tenant, to the same agent as the old one, active now
     * @throws GrantException {@code NOT_FOUND} (no such instance for this tenant, or another user's), {@code FORBIDDEN},
     *                        {@code INACTIVE} (the new grant is not usable), {@code INVALID_REQUEST} (the instance does
     *                        not wait for re-authorization, or changed meanwhile), {@code UNAVAILABLE}
     */
    public InstanceAuthority reauthorize(String tenant, Subject actor, Ulid instanceId, Ulid newGrant,
                                         String correlationId) {
        InstanceAuthority authority;
        try {
            authority = store.find(instanceId).orElse(null);
        } catch (RuntimeException e) {
            throw new GrantException(GrantException.Code.UNAVAILABLE, "the instance authority could not be read", e);
        }
        if (authority == null || !authority.tenant().equals(tenant) || !authority.delegated()) {
            throw new GrantException(GrantException.Code.NOT_FOUND, "no such instance");
        }
        if (!authority.subject().equals(actor)) {
            throw new GrantException(GrantException.Code.FORBIDDEN, "only the user who started the instance renews it");
        }
        if (authority.state() != InstanceAuthority.State.REAUTHORIZATION_REQUIRED) {
            throw new GrantException(GrantException.Code.INVALID_REQUEST,
                    "the instance is not waiting for re-authorization");
        }
        DelegationGrant replacement = activeGrantOf(newGrant, tenant, actor);
        DelegationGrant old = grants.find(authority.grantId()).orElseThrow(
                () -> new GrantException(GrantException.Code.UNAVAILABLE, "the previous grant is missing"));
        if (!replacement.agent().equals(old.agent()) || replacement.parentId() != null) {
            throw new GrantException(GrantException.Code.FORBIDDEN,
                    "the new grant must be the user's own grant to the same agent");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("previousGrantId", authority.grantId().value());
        attributes.put("grantId", newGrant.value());
        try {
            audit.record(actor, RENEWED_EVENT, IdentityAudit.INSTANCE_AUTHORITY, instanceId.value(), tenant,
                    correlationId, attributes);
        } catch (RuntimeException e) {
            throw new GrantException(GrantException.Code.UNAVAILABLE, "the renewal could not be recorded", e);
        }
        if (!store.rebind(instanceId, authority.grantId(), newGrant, now())) {
            throw new GrantException(GrantException.Code.INVALID_REQUEST, "the instance changed; read it and try again");
        }
        return store.find(instanceId).orElseThrow();
    }

    /** The authority of an instance, for the inspect output and for tests. */
    public Optional<InstanceAuthority> find(Ulid instanceId) {
        return store.find(instanceId);
    }

    /** The authority of the instance the engine knows under this id: how a task's callback finds its identity again. */
    public Optional<InstanceAuthority> findByEngineInstanceId(String engineInstanceId) {
        return store.findByEngineInstanceId(engineInstanceId);
    }

    /**
     * Re-establishes the identity of the instance the engine knows under this id, from the registry, and enters it on
     * this thread. This is how a task that calls back after a restart (or any time) gets its subject, grant and
     * correlation id back without any of them travelling in workflow state. Empty when the instance carries no authority
     * or the lookup fails (the failure is logged; the caller then runs without identity context, never with a guessed one).
     * Close the returned scope when the work ends.
     */
    public Optional<AuthorityScope> restore(String engineInstanceId) {
        if (engineInstanceId == null || engineInstanceId.isBlank()) {
            return Optional.empty();
        }
        try {
            return store.findByEngineInstanceId(engineInstanceId).map(AuthorityScope::enter);
        } catch (RuntimeException e) {
            LOG.warnf("the identity of engine instance %s could not be restored (%s)", engineInstanceId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
