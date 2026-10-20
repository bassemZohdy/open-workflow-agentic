package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.Instant;
import java.util.Optional;

/**
 * Which grant issued which delegated token (ADR 0021 H1). The identity provider cannot name our grant in the token, so the
 * exchanger records {@code (issuer, jti) -> grant id} <em>before</em> it returns the token, and every resource that admits
 * the token asks for that binding. Without it, "any active grant of this user to this agent" would revive a revoked grant
 * whenever an equivalent grant stayed active. Only ids are stored, never the token text.
 *
 * <p>A failure of either method is a {@link RuntimeException}; the exchanger then discards the token and the guard refuses
 * it. A binding is written once and never changed: binding the same token to another grant is refused.
 */
public interface IssuedTokenBindings {

    /** Records that {@code grantId} issued the token. Fails when the token is already bound to a different grant. */
    void bind(String issuer, String tokenId, Ulid grantId, String tenant, Instant expiresAt);

    /** The grant that issued the token, or empty when no binding exists. */
    Optional<Ulid> grantOf(String issuer, String tokenId);
}
