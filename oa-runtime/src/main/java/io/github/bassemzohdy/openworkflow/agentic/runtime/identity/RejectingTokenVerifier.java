package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

/** The verifier of a deployment with no issuer configured: every token is refused (fail closed). */
public final class RejectingTokenVerifier implements TokenVerifier {

    @Override
    public VerifiedToken verify(String token, String expectedAudience) {
        throw new TokenRejectedException(TokenRejectedException.Reason.VERIFIER_UNAVAILABLE);
    }
}
