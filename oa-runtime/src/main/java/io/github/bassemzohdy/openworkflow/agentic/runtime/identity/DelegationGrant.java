package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A delegation grant: a user lets an agent act for them within scopes until an expiry (ADR 0021 section 1). It is a
 * record of our own, not a token. A grant may have a parent: an agent that holds a grant delegates part of it to
 * another agent, which can only narrow it. The chain lists the agents from the first delegate to this grant's agent.
 *
 * @param id        the grant id
 * @param tenant    the tenant of the user
 * @param delegator the user whose authority is delegated (kind USER)
 * @param agent     the delegate
 * @param scopes    the scopes delegated; at least one
 * @param parentId  the grant this one narrows, or null for a grant given by the user
 * @param chain     agent ids from the first delegate to {@code agent}; one entry for a root grant
 * @param status    ACTIVE until revoked or marked expired
 * @param createdAt when the grant was created
 * @param createdBy who created it: the user for a root grant, the parent's agent for a child
 * @param expiresAt when the grant stops being valid; the grant is expired from this instant
 * @param revokedAt when it was revoked, or null
 * @param revokeReason why, or null
 * @param version   row version
 */
public record DelegationGrant(
        Ulid id,
        String tenant,
        Subject delegator,
        Subject agent,
        Set<String> scopes,
        Ulid parentId,
        List<String> chain,
        Status status,
        Instant createdAt,
        Subject createdBy,
        Instant expiresAt,
        Instant revokedAt,
        String revokeReason,
        long version) {

    public static final int MAX_DEPTH = 8;

    /** The stored status. A grant whose expiry has passed is expired even while this still says ACTIVE. */
    public enum Status { ACTIVE, REVOKED, EXPIRED }

    public DelegationGrant {
        Objects.requireNonNull(id, "id");
        tenant = Checks.requireText("tenant", tenant, 256);
        Objects.requireNonNull(delegator, "delegator");
        if (delegator.kind() != SubjectKind.USER) {
            throw new IllegalArgumentException("a grant is given by a user");
        }
        Objects.requireNonNull(agent, "agent");
        if (agent.kind() == SubjectKind.USER) {
            throw new IllegalArgumentException("a grant is given to an agent, a workflow or a service");
        }
        scopes = Checks.copyTokens("scopes", scopes, AgentTaskContract.MAX_ALLOW_LIST_SIZE, AgentTaskContract.TOKEN);
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("a grant needs at least one scope");
        }
        chain = Checks.copyList("chain", chain, MAX_DEPTH);
        if (chain.isEmpty() || !chain.get(chain.size() - 1).equals(agent.id())) {
            throw new IllegalArgumentException("the chain must end with the grant's agent");
        }
        if ((parentId == null) != (chain.size() == 1)) {
            throw new IllegalArgumentException("a root grant has a chain of one agent, a child has more");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("a grant must expire after it is created");
        }
    }

    /** True when the grant is ACTIVE and {@code now} is before its expiry. */
    public boolean activeAt(Instant now) {
        return status == Status.ACTIVE && now.isBefore(expiresAt);
    }

    /** The scopes asked for, if they are all delegated by this grant. */
    public boolean covers(Set<String> requested) {
        return scopes.containsAll(requested);
    }

    @Override
    public String toString() {
        return "DelegationGrant[" + id + " " + delegator.id() + " -> " + agent.id() + " " + status + "]";
    }
}
