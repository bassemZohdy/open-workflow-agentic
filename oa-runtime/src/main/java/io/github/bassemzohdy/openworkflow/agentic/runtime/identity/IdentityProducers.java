package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * CDI wiring of the identity package. The classes themselves have no CDI annotations (except where noted) and can be
 * built without a container.
 */
@ApplicationScoped
public class IdentityProducers {

    @Inject
    IdentitySettings settings;

    /**
     * The token verifier. With an issuer, it needs the JWKS URI and the API audience too; a partial configuration is a
     * startup error, not a verifier that accepts less than it should. With no issuer, every token is refused.
     */
    @Produces
    @Singleton
    public TokenVerifier tokenVerifier() {
        if (settings.issuer().isEmpty()) {
            return new RejectingTokenVerifier();
        }
        String jwks = settings.jwksUri().orElseThrow(() -> new IllegalStateException(
                "agentic.identity.issuer is set but agentic.identity.jwks-uri is not"));
        settings.audience().orElseThrow(() -> new IllegalStateException(
                "agentic.identity.issuer is set but agentic.identity.audience is not"));
        return JwtTokenVerifier.forJwks(settings.issuer().get(), jwks, Clock.systemUTC(), settings.clockSkew(),
                settings.tenantClaim(), settings.subjectKindClaim(), settings.jwksTimeout());
    }
}
