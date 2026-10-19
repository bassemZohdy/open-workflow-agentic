package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The claims of a token whose signature, issuer, audience and time window were verified. The token text is not kept.
 * Subject, tenant and scopes of a request come from here and from nowhere else (docs/policy.md: scopes are trusted as
 * given, so the caller must take them from a verified token).
 *
 * @param issuer     the issuer, equal to the configured one
 * @param subject    the principal: the user for a user's token, and for a delegated token the user on whose behalf it acts
 * @param audiences  the audiences of the token; the verifier checked that the expected one is among them
 * @param scopes     the scopes granted, already filtered to the scope token syntax of the policy context
 * @param tenant     the tenant claim, or null when the token has none
 * @param issuedAt   {@code iat}, or null
 * @param expiresAt  {@code exp}
 * @param tokenId    {@code jti}, or null
 * @param actors     the delegation chain from the {@code act} claim, nearest actor first; empty for a user's own token
 */
public record VerifiedToken(
        String issuer,
        Subject subject,
        Set<String> audiences,
        Set<String> scopes,
        String tenant,
        Instant issuedAt,
        Instant expiresAt,
        String tokenId,
        List<String> actors) {

    public VerifiedToken {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        audiences = Set.copyOf(audiences);
        scopes = Set.copyOf(scopes);
        Objects.requireNonNull(expiresAt, "expiresAt");
        actors = List.copyOf(actors);
    }

    /** True when an actor (an agent or a service) acts on behalf of the subject. */
    public boolean isDelegated() {
        return !actors.isEmpty();
    }

    /** The nearest actor, or null for a user's own token. */
    public String actor() {
        return actors.isEmpty() ? null : actors.get(0);
    }
}
