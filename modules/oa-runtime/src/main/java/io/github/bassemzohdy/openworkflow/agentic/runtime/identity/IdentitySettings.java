package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuration of identity and delegation (WP-102, docs/identity.md). Everything defaults to the safe side: with no
 * issuer every token is refused, and delegated tokens live at most 300 seconds.
 *
 * <pre>
 * agentic.identity.issuer=https://sso.example/realms/agentic
 * agentic.identity.jwks-uri=https://sso.example/realms/agentic/protocol/openid-connect/certs
 * agentic.identity.audience=oa-api
 * agentic.identity.tenant-claim=tenant
 * agentic.identity.token-endpoint=https://sso.example/realms/agentic/protocol/openid-connect/token
 * agentic.identity.exchange.client-id=oa-agent-runtime
 * agentic.identity.exchange.client-secret-name=exchange-client-secret
 * agentic.secrets.dir=/var/run/secrets/agentic
 * </pre>
 */
@ConfigMapping(prefix = "agentic.identity")
public interface IdentitySettings {

    /** The {@code iss} value of accepted tokens. Without it, every token is refused. */
    Optional<String> issuer();

    /** Where the issuer publishes its keys. Must be https, or http for a loopback host. Required with the issuer. */
    Optional<String> jwksUri();

    /** The audience of this API: a user's token must name it. Required with the issuer. */
    Optional<String> audience();

    /**
     * The audience of the governed agent-run resource ({@code POST /agents/research}) for delegated tokens (WP-103).
     * A delegated token is admitted there only through {@link AgentResourceAdmission}, and only when this audience is
     * set; without it the resource accepts user tokens alone. It should differ from {@link #audience()}.
     */
    Optional<String> agentResourceAudience();

    /** The claim that holds the tenant of the caller. A user's token without it is refused. */
    @WithDefault("tenant")
    String tenantClaim();

    /** The claim that holds the subject kind. A token without it is a USER token. */
    @WithDefault("sub_kind")
    String subjectKindClaim();

    /** Accepted difference between the issuer's clock and ours, at most five minutes. */
    @WithDefault("PT30S")
    Duration clockSkew();

    /** Connect and read timeout of the JWKS fetch. */
    @WithDefault("PT5S")
    Duration jwksTimeout();

    /** Longest lifetime of a delegation grant. A longer request is refused. */
    @WithDefault("P30D")
    Duration grantMaxLifetime();

    /** Cron expression of the sweep that marks expired grants (used by {@code GrantExpiryJob}). */
    @WithDefault("0 */5 * * * ?")
    String expirySweepCron();

    /** The token endpoint used for the RFC 8693 exchange. */
    Optional<String> tokenEndpoint();

    /** Settings of the RFC 8693 client. */
    Exchange exchange();

    interface Exchange {

        /** The OAuth client id of the agent runtime at the token endpoint. */
        Optional<String> clientId();

        /** The name of the file (in {@code agentic.secrets.dir}) that holds the client secret. */
        Optional<String> clientSecretName();

        /** Timeout of one exchange request. */
        @WithDefault("PT5S")
        Duration timeout();

        /** Maximum number of in-flight physical token requests, including timed-out calls still stopping. */
        @WithDefault("16")
        int maxConcurrent();

        /** Enable the token-endpoint circuit breaker. */
        @WithDefault("true")
        boolean circuitEnabled();

        /** Consecutive endpoint failures needed to open the circuit. */
        @WithDefault("5")
        int circuitFailureThreshold();

        /** How long an open circuit refuses requests before one recovery probe. */
        @WithDefault("PT30S")
        Duration circuitOpenFor();
    }
}
