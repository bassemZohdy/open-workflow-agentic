package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;

/**
 * CDI wiring of the identity package. The classes themselves have no CDI annotations (except where noted) and can be
 * built without a container.
 */
@ApplicationScoped
public class IdentityProducers {

    @Inject
    IdentitySettings settings;

    @Inject
    SecretSettings secretSettings;

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

    /** The audit writer of the identity package. Injected by the trail's concrete type (see AuditProducers). */
    @Produces
    @Singleton
    public IdentityAudit identityAudit(AuditTrail trail) {
        return new IdentityAudit(trail, Clock.systemUTC(), UlidGenerator.shared());
    }

    @Produces
    @Singleton
    public DelegationGrantStore delegationGrantStore(DataSource dataSource) {
        return new DelegationGrantStore(dataSource);
    }

    @Produces
    @Singleton
    public DelegationGrants delegationGrants(DelegationGrantStore store, IdentityAudit audit) {
        return new DelegationGrants(store, audit, Clock.systemUTC(), UlidGenerator.shared(),
                settings.grantMaxLifetime());
    }

    @Produces
    @Singleton
    public InstanceAuthorityStore instanceAuthorityStore(DataSource dataSource) {
        return new InstanceAuthorityStore(dataSource);
    }

    /**
     * The identity of instances. Its bean types include {@code ResumeGate}, so the reconciler asks it before a restored
     * instance continues.
     */
    @Produces
    @Singleton
    public InstanceAuthorities instanceAuthorities(InstanceAuthorityStore store, DelegationGrants grants,
                                                   IdentityAudit audit) {
        return new InstanceAuthorities(store, grants, audit, Clock.systemUTC());
    }

    /**
     * The secret provider: files in {@code agentic.secrets.dir}. Without the setting every secret is unavailable, so
     * work that needs one pauses or fails instead of running without it.
     */
    @Produces
    @Singleton
    public SecretProvider secretProvider(IdentityAudit audit) {
        if (secretSettings.dir().isEmpty()) {
            return name -> {
                throw new SecretUnavailableException("agentic.secrets.dir is not set");
            };
        }
        try {
            return new FileSecretProvider(Path.of(secretSettings.dir().get()), Clock.systemUTC(), secretSettings.ttl(),
                    secretSettings.maxStale(), audit);
        } catch (IllegalArgumentException e) {
            org.jboss.logging.Logger.getLogger(IdentityProducers.class).errorf(
                    "agentic.secrets.dir is not usable (%s); every secret is unavailable", e.getMessage());
            return name -> {
                throw new SecretUnavailableException("the secrets directory is not usable");
            };
        }
    }

    /**
     * The token exchanger. Without a token endpoint every exchange is refused as unavailable. With one, the client id and
     * the name of the client secret are required, and so are the issuer settings of the verifier (the exchanged token is
     * verified before it is used).
     */
    @Produces
    @Singleton
    public TokenExchanger tokenExchanger(SecretProvider secrets, DelegationGrants grants, TokenVerifier verifier,
                                         IdentityAudit audit) {
        if (settings.tokenEndpoint().isEmpty()) {
            return request -> {
                throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                        "agentic.identity.token-endpoint is not set");
            };
        }
        if (settings.issuer().isEmpty()) {
            throw new IllegalStateException(
                    "agentic.identity.token-endpoint is set but agentic.identity.issuer is not");
        }
        IdentitySettings.Exchange exchange = settings.exchange();
        return new Rfc8693TokenExchanger(settings.tokenEndpoint().get(),
                exchange.clientId().orElseThrow(() -> new IllegalStateException(
                        "agentic.identity.exchange.client-id is required with a token endpoint")),
                exchange.clientSecretName().orElseThrow(() -> new IllegalStateException(
                        "agentic.identity.exchange.client-secret-name is required with a token endpoint")),
                secrets, grants, verifier, audit, Clock.systemUTC(), exchange.timeout());
    }
}
