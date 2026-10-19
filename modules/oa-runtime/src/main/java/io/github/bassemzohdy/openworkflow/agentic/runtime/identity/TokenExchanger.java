package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

/**
 * Exchanges the authority a runtime holds for a short-lived, narrow token (RFC 8693). The runtime depends on this port;
 * {@link Rfc8693TokenExchanger} is the implementation for an authorization server such as Keycloak 26.2 or later.
 *
 * <p>Contract: the returned token (1) is for exactly the audience asked, (2) holds only scopes that were asked and that
 * the grant holds, (3) lives at most 300 seconds and never beyond the grant's expiry, (4) records the delegation chain,
 * (5) came with no refresh token. If any of that cannot be shown, the call throws {@link ExchangeException} and no
 * token leaves the exchanger. The user's own token is never returned (no passthrough).
 */
public interface TokenExchanger {

    /** The longest lifetime of an issued token (ADR 0021). */
    long MAX_LIFETIME_SECONDS = 300;

    /**
     * @throws ExchangeException when no acceptable token was obtained; {@code code()} says why
     */
    IssuedToken exchange(ExchangeRequest request);
}
