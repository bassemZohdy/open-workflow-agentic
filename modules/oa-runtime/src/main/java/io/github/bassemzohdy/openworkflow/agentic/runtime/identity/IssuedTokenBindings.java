package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Which grant issued which delegated token (ADR 0021 H1). The identity provider cannot name our grant in the token, so the
 * exchanger records {@code (issuer, jti) -> (grant, tenant, signed expiry)} <em>before</em> it returns the token, and every
 * resource that admits the token asks for that binding and compares all of it. Without it, "any active grant of this user to
 * this agent" would revive a revoked grant whenever an equivalent grant stayed active. Only ids and times are stored, never
 * the token text.
 *
 * <p>A binding is immutable. Binding the same {@code (issuer, jti)} again is accepted only when grant, tenant and signed
 * expiry are all identical (an idempotent retry); any difference is refused, so an old proof cannot be re-bound to another
 * grant or extended. A failure of either method is a {@link RuntimeException}; the exchanger then discards the token and the
 * guard refuses it.
 */
public interface IssuedTokenBindings {

    /** What was recorded for one token. */
    record Binding(Ulid grantId, String tenant, Instant expiresAt) {
        public Binding {
            Objects.requireNonNull(grantId, "grantId");
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * Records the binding durably and independently of any caller transaction: the binding is committed when this returns.
     * Fails when the token is already bound to anything different.
     */
    void bind(String issuer, String tokenId, Binding binding);

    /** The binding of the token, or empty when none exists. */
    Optional<Binding> find(String issuer, String tokenId);
}
