package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a resource server runs on a delegated token before it acts (ADR 0021: audience binding, no token passthrough).
 * A resource is one audience. It accepts a token only if:
 * <ol>
 *   <li>the token verifies (signature, issuer, time window), and names this resource's audience <em>and no other</em>
 *       (a token for several audiences could be replayed at another resource);</li>
 *   <li>it is a delegated token: it names an actor. A user's own token is not accepted here, so a runtime that passes
 *       the user's token through to a tool fails at the tool;</li>
 *   <li>it has an id ({@code jti}) and an issue time, and was issued for at most 300 seconds;</li>
 *   <li>it holds every scope the call needs;</li>
 *   <li>when a grant lookup is configured, an active grant of this user to this actor covers those scopes now (so a
 *       revoked grant stops a token that is still unexpired);</li>
 *   <li>it has not been used more than {@code maxUses} times (default 1).</li>
 * </ol>
 * The replay check runs last, so a refused token does not use up a use. Any failure of a dependency refuses.
 */
public final class ResourceTokenGuard {

    private final TokenVerifier verifier;
    private final String audience;
    private final ReplayCache replay;
    private final DelegationGrants grants;
    private final Clock clock;
    private final int maxUses;

    /**
     * @param grants  checked against the token's user, actor and tenant; null to skip the grant lookup
     * @param maxUses how many times one token may be accepted; 1 makes a token a one-call credential
     */
    public ResourceTokenGuard(TokenVerifier verifier, String audience, ReplayCache replay, DelegationGrants grants,
                              Clock clock, int maxUses) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("the resource audience is required");
        }
        this.audience = audience;
        this.replay = Objects.requireNonNull(replay, "replay");
        this.grants = grants;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxUses < 1) {
            throw new IllegalArgumentException("maxUses must be at least 1");
        }
        this.maxUses = maxUses;
    }

    /**
     * @param bearer         the token text from the call
     * @param requiredScopes the scopes the call needs; may be empty only for a call that needs no scope
     * @throws TokenRejectedException the token is refused; {@code reason()} says why
     */
    public VerifiedToken admit(String bearer, Set<String> requiredScopes) {
        Objects.requireNonNull(requiredScopes, "requiredScopes");
        VerifiedToken token = verifier.verify(bearer, audience);
        if (!token.audiences().equals(Set.of(audience))) {
            throw new TokenRejectedException(TokenRejectedException.Reason.AUDIENCE);
        }
        if (!token.isDelegated()) {
            throw new TokenRejectedException(TokenRejectedException.Reason.WRONG_TOKEN_KIND);
        }
        if (token.tokenId() == null || token.tokenId().isBlank() || token.issuedAt() == null) {
            throw new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM);
        }
        Duration lifetime = Duration.between(token.issuedAt(), token.expiresAt());
        if (lifetime.isNegative() || lifetime.getSeconds() > TokenExchanger.MAX_LIFETIME_SECONDS) {
            throw new TokenRejectedException(TokenRejectedException.Reason.EXPIRED);
        }
        if (!token.scopes().containsAll(requiredScopes)) {
            throw new TokenRejectedException(TokenRejectedException.Reason.SCOPE);
        }
        Instant now = clock.instant();
        if (grants != null) {
            checkGrant(token, requiredScopes);
        }
        boolean allowed;
        try {
            allowed = replay.use(token.issuer() + "|" + token.tokenId(), token.expiresAt(), maxUses, now);
        } catch (RuntimeException e) {
            throw new TokenRejectedException(TokenRejectedException.Reason.VERIFIER_UNAVAILABLE, e);
        }
        if (!allowed) {
            throw new TokenRejectedException(TokenRejectedException.Reason.REPLAYED);
        }
        return token;
    }

    private void checkGrant(VerifiedToken token, Set<String> requiredScopes) {
        if (token.tenant() == null) {
            throw new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM);
        }
        List<DelegationGrant> active;
        try {
            active = grants.activeFor(token.tenant(), token.subject().id(), token.actor());
        } catch (RuntimeException e) {
            throw new TokenRejectedException(TokenRejectedException.Reason.VERIFIER_UNAVAILABLE, e);
        }
        boolean covered = active.stream().anyMatch(g -> g.covers(requiredScopes) && g.covers(token.scopes()));
        if (!covered) {
            throw new TokenRejectedException(TokenRejectedException.Reason.GRANT_INACTIVE);
        }
    }
}
