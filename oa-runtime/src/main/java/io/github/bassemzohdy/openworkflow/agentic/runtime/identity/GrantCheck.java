package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Objects;

/**
 * The answer to "may this grant be used now?". It is a typed value so that a caller cannot treat "unknown" or "expired"
 * as "fine": only {@link Active} permits.
 */
public sealed interface GrantCheck {

    /** Why a grant cannot be used. */
    enum Reason {
        /** No grant with this id. */
        UNKNOWN,
        /** The grant's expiry has passed (whether or not the sweep has marked it). */
        EXPIRED,
        /** The grant, or the grant it narrows, was revoked. */
        REVOKED,
        /** A grant above this one in the chain is revoked or expired, which ends this one too. */
        ANCESTOR_INACTIVE
    }

    /** The grant is active and so are all grants above it. */
    record Active(DelegationGrant grant) implements GrantCheck {
        public Active {
            Objects.requireNonNull(grant, "grant");
        }
    }

    /** The grant cannot be used. */
    record Inactive(Ulid grantId, Reason reason) implements GrantCheck {
        public Inactive {
            Objects.requireNonNull(grantId, "grantId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    default boolean active() {
        return this instanceof Active;
    }
}
