package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.Objects;

/**
 * The identity an instance runs under, as identifiers only (table {@code identity.instance_authority}). It is what is
 * persisted and what is re-established after a restart: no token, no secret. A fresh token is obtained from the grant
 * when one is needed ({@link TokenExchanger}).
 *
 * @param instanceId    the registry instance
 * @param tenant        the tenant of the instance
 * @param subject       who started it (the user); the effective principal of everything the instance does
 * @param grantId       the delegation grant it runs under, or null when it carries no delegated authority
 * @param correlationId the correlation id of the request that started it, or null
 * @param state         AUTHORIZED, or REAUTHORIZATION_REQUIRED when the grant ended while the instance waited
 * @param stateReason   why the state is not AUTHORIZED (a short code such as {@code grant.expired}), or null
 * @param stateSince    when the state last changed
 * @param createdAt     when the row was created
 */
public record InstanceAuthority(Ulid instanceId, String tenant, Subject subject, Ulid grantId, String correlationId,
                                State state, String stateReason, Instant stateSince, Instant createdAt) {

    /** Whether the instance may continue under its grant. */
    public enum State { AUTHORIZED, REAUTHORIZATION_REQUIRED }

    public InstanceAuthority {
        Objects.requireNonNull(instanceId, "instanceId");
        tenant = Checks.requireText("tenant", tenant, 256);
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(state, "state");
        if (state == State.REAUTHORIZATION_REQUIRED && (grantId == null || stateReason == null)) {
            throw new IllegalArgumentException("re-authorization needs a grant and a reason");
        }
        Objects.requireNonNull(stateSince, "stateSince");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** True when the instance runs under a delegation grant. */
    public boolean delegated() {
        return grantId != null;
    }

    @Override
    public String toString() {
        return "InstanceAuthority[" + instanceId + " " + subject.id() + " grant=" + grantId + " " + state + "]";
    }
}
