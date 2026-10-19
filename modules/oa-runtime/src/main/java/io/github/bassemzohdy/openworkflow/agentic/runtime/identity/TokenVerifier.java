package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

/**
 * Verifies a bearer token. The runtime depends on this port, not on a library, so the verification is backed by the
 * issuer's JWKS in production ({@link JwtTokenVerifier}), by the testkit's fake issuer in tests (the same adapter,
 * pointed at the fake's JWKS), and a deployment without an issuer refuses every token ({@link RejectingTokenVerifier}).
 *
 * <p>Contract: return only for a token that is signed by a trusted key, is inside its time window, comes from the
 * configured issuer and names {@code expectedAudience}. Every other outcome, including a failure to decide, throws
 * {@link TokenRejectedException}. An implementation never returns for a token it could not check.
 */
public interface TokenVerifier {

    /**
     * @param token            the compact JWT, without the {@code Bearer} prefix
     * @param expectedAudience the audience of the resource that receives the token
     * @throws TokenRejectedException if the token is not acceptable for that audience, or could not be checked
     */
    VerifiedToken verify(String token, String expectedAudience);
}
