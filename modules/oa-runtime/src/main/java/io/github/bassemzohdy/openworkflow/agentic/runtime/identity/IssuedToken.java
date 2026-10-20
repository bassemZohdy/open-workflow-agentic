package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A short-lived token obtained by exchange. It is held in memory only: it is never written to the database, a log, an
 * audit event or workflow state (docs/identity.md section 7). There is no refresh token: when this expires, the runtime
 * re-checks the grant and exchanges again.
 *
 * @param accessToken the bearer token; a {@link Credential}, so printing this record does not show it
 * @param audience    the one audience of the token
 * @param scopes      the scopes of the token; a subset of what was asked
 * @param expiresAt   the token's expiry: at most 300 seconds after issue and never after the grant's expiry
 * @param issuer      the verified issuer of the token (with the {@code jti} it is the key of the grant binding)
 * @param tokenId     the {@code jti}
 * @param actors      the delegation chain recorded in the token, nearest actor first
 * @param grantId     the grant that authorized the exchange
 */
public record IssuedToken(Credential accessToken, String audience, Set<String> scopes, Instant expiresAt,
                          String issuer, String tokenId, List<String> actors, Ulid grantId) {

    public IssuedToken {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(audience, "audience");
        scopes = Set.copyOf(scopes);
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(tokenId, "tokenId");
        actors = List.copyOf(actors);
        Objects.requireNonNull(grantId, "grantId");
    }

    /** True while {@code now} is before the expiry. The runtime stops using a token at its expiry, not later. */
    public boolean usableAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
