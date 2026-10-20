package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakRealm.Answer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrantStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityAudit;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InMemoryReplayCache;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JwtTokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ResourceTokenGuard;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.SecretProvider;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A disposable realm plus the product's own identity components wired against it: the real {@link JwtTokenVerifier},
 * {@link Rfc8693TokenExchanger}, {@link DelegationGrants} (PostgreSQL) and {@link ResourceTokenGuard}. No fake issuer,
 * no mapper that writes authority into a token by hand.
 */
final class KeycloakHarness implements AutoCloseable {

    static final String TOOL = "tool-cases";
    static final String OTHER_TOOL = "tool-search";
    static final Set<String> READ = Set.of("cases:read");
    static final Set<String> READ_WRITE = Set.of("cases:read", "cases:write");

    final KeycloakSupport.Config config;
    final KeycloakRealm realm;
    final Subject alice;
    final Subject agent;
    final Subject otherAgent;
    final DelegationGrants grants;
    final IdentityAudit audit;
    final Rfc8693TokenExchanger exchanger;
    final JwtTokenVerifier verifier;

    KeycloakHarness(KeycloakSupport.Config config) {
        this.config = config;
        realm = new KeycloakRealm(config);
        try {
            realm.build();
        } catch (RuntimeException e) {
            realm.close();
            throw e;
        }
        alice = new Subject(SubjectKind.USER, realm.aliceId);
        agent = new Subject(SubjectKind.AGENT, realm.agentUserId);
        otherAgent = new Subject(SubjectKind.AGENT, realm.otherAgentUserId);
        Clock clock = Clock.systemUTC();
        audit = new IdentityAudit(event -> { }, clock, UlidGenerator.shared());
        grants = new DelegationGrants(new DelegationGrantStore(KeycloakSupport.dataSource()), audit, clock,
                UlidGenerator.shared(), Duration.ofDays(30));
        verifier = verifier(clock);
        exchanger = exchanger(clock, UnaryOperator.identity());
    }

    JwtTokenVerifier verifier(Clock clock) {
        return JwtTokenVerifier.forJwks(realm.issuer(), realm.jwksUri(), clock, Duration.ofSeconds(30), "tenant",
                "sub_kind", Duration.ofSeconds(5));
    }

    /** An exchanger for the agent runtime's client, with its own clock and optionally a wrapped verifier. */
    Rfc8693TokenExchanger exchanger(Clock clock, UnaryOperator<TokenVerifier> wrap) {
        SecretProvider secrets = name -> Credential.of(KeycloakRealm.AGENT_SECRET);
        return new Rfc8693TokenExchanger(realm.tokenEndpoint(), KeycloakRealm.AGENT, "exchange-client-secret", secrets,
                grants, wrap.apply(verifier(clock)), audit, clock, Duration.ofSeconds(10));
    }

    /** A resource server for {@code audience}: one use per token, grants checked. */
    ResourceTokenGuard guard(String audience) {
        return new ResourceTokenGuard(verifier, audience, new InMemoryReplayCache(100), grants, Clock.systemUTC(), 1);
    }

    DelegationGrant grant(Set<String> scopes, Duration lifetime) {
        return grant(KeycloakRealm.USER_TENANT, agent, scopes, lifetime);
    }

    DelegationGrant grant(String tenant, Subject to, Set<String> scopes, Duration lifetime) {
        return grants.grant(tenant, alice, alice, to, scopes, Instant.now().plus(lifetime), "it-" + System.nanoTime());
    }

    /** The agent runtime's current workload token. */
    Credential workload() {
        return Credential.of(accessToken(realm.workloadToken()));
    }

    ExchangeRequest request(String subjectToken, String audience, Set<String> scopes, DelegationGrant grant) {
        return new ExchangeRequest(Credential.of(subjectToken), workload(), audience, scopes, grant);
    }

    /** Alice's token from the real consent flow with {@code delegation:client:oa-agent-runtime}. */
    String delegatedSubject(String client) {
        KeycloakRealm.Login login = realm.delegatedLogin(client, KeycloakRealm.AGENT, true);
        return accessToken(login.token());
    }

    static String accessToken(Answer answer) {
        assertEquals(200, answer.status(), () -> "token request: " + answer.body());
        return answer.text("access_token");
    }

    @Override
    public void close() {
        realm.close();
    }
}
