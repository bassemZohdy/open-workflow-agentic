package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.PermissionWideningException;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GrantException.Code;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Creates, delegates, revokes, expires and looks up delegation grants (WP-102, docs/identity.md section 3).
 *
 * <p>The rules, each also enforced by database triggers (V60):
 * <ul>
 *   <li>Only the user gives a root grant: the actor must be the delegator. A child grant is made by the parent's
 *       agent: the actor must be that agent.</li>
 *   <li>Delegation only narrows: the child's scopes are a subset of the parent's and its expiry is not later. A
 *       violation is a {@link PermissionWideningException}.</li>
 *   <li>The chain is recorded; an agent that is already in the chain cannot be added (no cycle), and the depth is
 *       limited to {@value DelegationGrant#MAX_DEPTH}.</li>
 *   <li>A grant lasts at most the configured maximum lifetime.</li>
 *   <li>Revoking a grant revokes everything delegated from it.</li>
 * </ul>
 *
 * <p>Authorization of the <i>caller</i> (who may call these methods, as which actor) belongs to the API or the policy
 * enforcement point in front of this service. This class checks only that the actor is the party the rule names.
 *
 * <p>Audit: every creation, revocation and expiry writes an event ({@code identity.grant.created},
 * {@code identity.grant.revoked}, {@code identity.grant.expired}). Creation is fail-closed: if the event cannot be
 * written, the grant is revoked again and the call fails. Revocation is fail-safe: the revocation stays even when its
 * event cannot be written, and the failure is logged at ERROR.
 */
public class DelegationGrants {

    public static final String CREATED = "identity.grant.created";
    public static final String REVOKED = "identity.grant.revoked";
    public static final String EXPIRED = "identity.grant.expired";

    private static final Logger LOG = Logger.getLogger(DelegationGrants.class);
    private static final Subject SYSTEM = new Subject(SubjectKind.SERVICE, "agentic-identity");

    private final DelegationGrantStore store;
    private final IdentityAudit audit;
    private final Clock clock;
    private final UlidGenerator ids;
    private final Duration maxLifetime;

    public DelegationGrants(DelegationGrantStore store, IdentityAudit audit, Clock clock, UlidGenerator ids,
                            Duration maxLifetime) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.maxLifetime = Objects.requireNonNull(maxLifetime, "maxLifetime");
        if (maxLifetime.isZero() || maxLifetime.isNegative()) {
            throw new IllegalArgumentException("the maximum grant lifetime must be positive");
        }
    }

    /**
     * A user lets an agent act for them.
     *
     * @param user    the delegating user; must equal {@code actor}
     * @param actor   who asks; the verified caller
     * @param agent   the delegate (kind AGENT, WORKFLOW or SERVICE)
     * @param scopes  the scopes delegated, a non-empty set of scope tokens
     * @param expires when the grant ends: after now, and at most the maximum lifetime from now
     * @param correlationId the request's correlation id, or null
     */
    public DelegationGrant grant(String tenant, Subject user, Subject actor, Subject agent, Set<String> scopes,
                                 Instant expires, String correlationId) {
        if (!user.equals(actor)) {
            throw new GrantException(Code.FORBIDDEN, "a root grant can only be given by the user themselves");
        }
        Instant now = now();
        validate(scopes, expires, now);
        if (expires.isAfter(now.plus(maxLifetime))) {
            throw new GrantException(Code.INVALID_REQUEST, "a grant lasts at most " + maxLifetime);
        }
        DelegationGrant grant = build(tenant, user, agent, scopes, null, List.of(agent.id()), now, actor, expires);
        return persist(grant, actor, correlationId);
    }

    /**
     * An agent delegates part of its grant to another agent. The child can only narrow the parent.
     *
     * @param parentId the grant being narrowed; its agent must equal {@code actor}
     * @throws PermissionWideningException if the scopes or the expiry exceed the parent's
     * @throws GrantException              {@code PARENT_INACTIVE}, {@code CYCLE}, {@code FORBIDDEN} or {@code NOT_FOUND}
     */
    public DelegationGrant delegate(Ulid parentId, Subject actor, Subject childAgent, Set<String> scopes,
                                    Instant expires, String correlationId) {
        Instant now = now();
        DelegationGrant parent = store.find(parentId).orElseThrow(
                () -> new GrantException(Code.NOT_FOUND, "no such grant"));
        if (!parent.agent().equals(actor)) {
            throw new GrantException(Code.FORBIDDEN, "only the agent that holds a grant can delegate from it");
        }
        GrantCheck check = check(parentId);
        if (!check.active()) {
            throw new GrantException(Code.PARENT_INACTIVE, "the parent grant is not active");
        }
        validate(scopes, expires, now);
        if (!parent.covers(scopes)) {
            throw new PermissionWideningException("the delegation would widen the parent grant: scopes not held by it");
        }
        if (expires.isAfter(parent.expiresAt())) {
            throw new PermissionWideningException("the delegation would outlive the parent grant");
        }
        if (parent.chain().contains(childAgent.id()) || parent.chain().size() >= DelegationGrant.MAX_DEPTH) {
            throw new GrantException(Code.CYCLE, "the agent is already in the delegation chain, or the chain is full");
        }
        List<String> chain = new ArrayList<>(parent.chain());
        chain.add(childAgent.id());
        DelegationGrant grant = build(parent.tenant(), parent.delegator(), childAgent, scopes, parentId, chain, now,
                actor, expires);
        return persist(grant, actor, correlationId);
    }

    /**
     * Revokes a grant and every grant delegated from it. The actor must be the user who gave it, the one who created
     * it, or the agent that holds it (an agent may give back what it holds).
     *
     * @return the grants revoked by this call (empty if it was not active)
     */
    public List<DelegationGrant> revoke(Ulid id, Subject actor, String reason, String correlationId) {
        DelegationGrant grant = store.find(id).orElseThrow(() -> new GrantException(Code.NOT_FOUND, "no such grant"));
        if (!actor.equals(grant.delegator()) && !actor.equals(grant.createdBy()) && !actor.equals(grant.agent())) {
            throw new GrantException(Code.FORBIDDEN,
                    "only the user, the creator or the holder of a grant can revoke it");
        }
        return revokeUnchecked(grant, actor, reason, correlationId);
    }

    /**
     * Revokes a grant on behalf of an operator (containment, M20). The caller must have been authorized for the
     * operation by the policy enforcement point; this method does not check the actor.
     */
    public List<DelegationGrant> revokeByOperator(Ulid id, Subject operator, String reason, String correlationId) {
        DelegationGrant grant = store.find(id).orElseThrow(() -> new GrantException(Code.NOT_FOUND, "no such grant"));
        return revokeUnchecked(grant, operator, reason, correlationId);
    }

    private List<DelegationGrant> revokeUnchecked(DelegationGrant grant, Subject actor, String reason,
                                                  String correlationId) {
        String text = reason == null || reason.isBlank() ? "revoked" : truncate(reason, 128);
        List<DelegationGrant> revoked;
        try {
            revoked = store.revokeSubtree(grant.id(), now(), actor.id(), text);
        } catch (StoreUnavailableException e) {
            throw new GrantException(Code.UNAVAILABLE, "the grant store is unavailable; nothing was revoked", e);
        }
        for (DelegationGrant each : revoked) {
            try {
                audit.record(actor, REVOKED, IdentityAudit.GRANT, each.id().value(), each.tenant(), correlationId,
                        attributes(each, Map.of("grant.reason", text, "grant.root", grant.id().value())));
            } catch (RuntimeException unrecorded) {
                // The revocation stands: containment must not depend on the audit trail being up.
                LOG.errorf(unrecorded, "grant %s was revoked but the audit event could not be written", each.id());
            }
        }
        return revoked;
    }

    /**
     * May this grant be used now? Active only if the grant is ACTIVE, its expiry has not passed, and every grant above
     * it is active too. Anything else is a typed {@link GrantCheck.Inactive}. A store failure is an exception, never
     * "active".
     */
    public GrantCheck check(Ulid id) {
        Instant now = now();
        Ulid current = id;
        DelegationGrant first = null;
        for (int depth = 0; depth <= DelegationGrant.MAX_DEPTH; depth++) {
            DelegationGrant grant = store.find(current).orElse(null);
            if (grant == null) {
                return new GrantCheck.Inactive(id, depth == 0 ? GrantCheck.Reason.UNKNOWN
                        : GrantCheck.Reason.ANCESTOR_INACTIVE);
            }
            if (first == null) {
                first = grant;
            }
            GrantCheck.Reason reason = inactiveReason(grant, now);
            if (reason != null) {
                return new GrantCheck.Inactive(id, depth == 0 ? reason : GrantCheck.Reason.ANCESTOR_INACTIVE);
            }
            if (grant.parentId() == null) {
                return new GrantCheck.Active(first);
            }
            current = grant.parentId();
        }
        return new GrantCheck.Inactive(id, GrantCheck.Reason.ANCESTOR_INACTIVE);
    }

    private static GrantCheck.Reason inactiveReason(DelegationGrant grant, Instant now) {
        return switch (grant.status()) {
            case REVOKED -> GrantCheck.Reason.REVOKED;
            case EXPIRED -> GrantCheck.Reason.EXPIRED;
            case ACTIVE -> now.isBefore(grant.expiresAt()) ? null : GrantCheck.Reason.EXPIRED;
        };
    }

    /** The grant, when it exists. */
    public java.util.Optional<DelegationGrant> find(Ulid id) {
        return store.find(id);
    }

    /** The active grants of a user for an agent, newest first. Each is checked against its ancestors. */
    public List<DelegationGrant> activeFor(String tenant, String userId, String agentId) {
        List<DelegationGrant> out = new ArrayList<>();
        for (DelegationGrant grant : store.activeFor(tenant, userId, agentId, now())) {
            if (check(grant.id()).active()) {
                out.add(grant);
            }
        }
        return out;
    }

    /**
     * Marks grants whose expiry has passed as EXPIRED and writes an {@code identity.grant.expired} event for each. A
     * grant is unusable from its expiry whether or not this runs ({@link #check} reads the clock), so the sweep is for
     * the record, not for safety.
     *
     * @return how many grants were marked
     */
    public int expireDue() {
        int total = 0;
        while (true) {
            List<DelegationGrant> expired;
            try {
                expired = store.markExpired(now(), 100);
            } catch (StoreUnavailableException e) {
                throw new GrantException(Code.UNAVAILABLE, "the grant store is unavailable", e);
            }
            for (DelegationGrant each : expired) {
                try {
                    audit.record(SYSTEM, EXPIRED, IdentityAudit.GRANT, each.id().value(), each.tenant(), null,
                            attributes(each, Map.of()));
                } catch (RuntimeException unrecorded) {
                    LOG.errorf(unrecorded, "grant %s expired but the audit event could not be written", each.id());
                }
            }
            total += expired.size();
            if (expired.size() < 100) {
                return total;
            }
        }
    }

    // ---- internals -----------------------------------------------------------------------------------------------------

    private DelegationGrant persist(DelegationGrant grant, Subject actor, String correlationId) {
        try {
            store.insert(grant);
        } catch (StoreUnavailableException e) {
            throw new GrantException(Code.UNAVAILABLE, "the grant store is unavailable; nothing was granted", e);
        }
        try {
            Map<String, String> extra = new LinkedHashMap<>();
            if (grant.parentId() != null) {
                extra.put("grant.parent", grant.parentId().value());
            }
            audit.record(actor, CREATED, IdentityAudit.GRANT, grant.id().value(), grant.tenant(), correlationId,
                    attributes(grant, extra));
        } catch (RuntimeException unrecorded) {
            LOG.errorf(unrecorded, "grant %s could not be audited; revoking it", grant.id());
            try {
                store.revokeSubtree(grant.id(), now(), SYSTEM.id(), "audit-unavailable");
            } catch (RuntimeException stillActive) {
                LOG.errorf(stillActive, "grant %s is active without an audit event and could not be revoked",
                        grant.id());
            }
            throw new GrantException(Code.UNAVAILABLE, "the audit trail is unavailable; nothing was granted",
                    unrecorded);
        }
        return grant;
    }

    private DelegationGrant build(String tenant, Subject user, Subject agent, Set<String> scopes, Ulid parent,
                                  List<String> chain, Instant now, Subject createdBy, Instant expires) {
        if (agent.kind() == SubjectKind.USER) {
            throw new GrantException(Code.INVALID_REQUEST, "a grant is given to an agent, a workflow or a service");
        }
        try {
            return new DelegationGrant(ids.next(), tenant, user, agent, scopes, parent, chain,
                    DelegationGrant.Status.ACTIVE, now, createdBy, expires, null, null, 0);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GrantException(Code.INVALID_REQUEST, "the grant is not valid: " + e.getMessage());
        }
    }

    private static void validate(Set<String> scopes, Instant expires, Instant now) {
        if (scopes == null || scopes.isEmpty()) {
            throw new GrantException(Code.INVALID_REQUEST, "a grant needs at least one scope");
        }
        if (scopes.size() > AgentTaskContract.MAX_ALLOW_LIST_SIZE) {
            throw new GrantException(Code.INVALID_REQUEST, "too many scopes");
        }
        for (String scope : scopes) {
            if (scope == null || !AgentTaskContract.TOKEN.matcher(scope).matches()) {
                throw new GrantException(Code.INVALID_REQUEST, "a scope is not a valid scope token");
            }
        }
        if (expires == null || !expires.isAfter(now)) {
            throw new GrantException(Code.INVALID_REQUEST, "the expiry must be in the future");
        }
    }

    private Map<String, String> attributes(DelegationGrant grant, Map<String, String> extra) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("grant.user", grant.delegator().id());
        attributes.put("grant.agent", grant.agent().id());
        attributes.put("grant.chain", String.join(">", grant.chain()));
        attributes.put("grant.expires-at", grant.expiresAt().toString());
        attributes.putAll(IdentityAudit.scopeAttributes(grant.scopes()));
        attributes.putAll(extra);
        return attributes;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
