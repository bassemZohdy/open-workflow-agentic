package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakRealm.Answer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrantStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityAudit;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JwtTokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.SecretProvider;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * WP-102: the product's own token-exchange client and token verifier against a REAL Keycloak (docs/identity.md
 * section 9). It is skipped unless {@code KEYCLOAK_URL} names a throw-away server (for example
 * {@code http://localhost:18080}, started with {@code bin/kc.sh start-dev} and a bootstrap admin admin/admin).
 *
 * <p>The realm {@code agentic-it} is deleted and rebuilt through the admin API ({@link KeycloakRealm}). Set
 * {@code KEYCLOAK_DELEGATION=true} when the server is Keycloak 26.8+ started with
 * {@code --features=token-exchange-delegation,parameterized-scopes}; the realm then also writes {@code may_act} into
 * the user's token.
 *
 * <p>These are characterization tests of what Keycloak does today, with the product code in the loop. Where the
 * server's behaviour differs from what ADR 0021 assumes, the test asserts the observed behaviour and says so, so that
 * a Keycloak change shows up as a failure instead of as a silent drift.
 */
@EnabledIfEnvironmentVariable(named = "KEYCLOAK_URL", matches = "https?://.+")
class KeycloakInteropIT {

    private static final String SECRET_NAME = "exchange-client-secret";
    private static final String TOOL = "tool-cases";
    private static final Set<String> READ = Set.of("cases:read");

    private static KeycloakRealm realm;
    private static boolean delegation;
    private static JwtTokenVerifier verifier;
    private static DelegationGrants grants;
    private static Rfc8693TokenExchanger exchanger;
    private static Subject alice;
    private static Subject agent;

    @BeforeAll
    static void realmAndClient() {
        delegation = Boolean.parseBoolean(System.getenv().getOrDefault("KEYCLOAK_DELEGATION", "false"));
        realm = new KeycloakRealm(System.getenv("KEYCLOAK_URL"), delegation);
        realm.build();
        alice = new Subject(SubjectKind.USER, realm.aliceId);
        agent = new Subject(SubjectKind.AGENT, realm.agentUserId);

        Clock clock = Clock.systemUTC();
        verifier = JwtTokenVerifier.forJwks(realm.issuer(), realm.jwksUri(), clock, Duration.ofSeconds(30), "tenant",
                "sub_kind", Duration.ofSeconds(5));
        IdentityAudit audit = new IdentityAudit(event -> { }, clock, UlidGenerator.shared());
        grants = new DelegationGrants(new DelegationGrantStore(dataSource()), audit, clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        SecretProvider secrets = name -> Credential.of(KeycloakRealm.AGENT_SECRET);
        exchanger = new Rfc8693TokenExchanger(realm.tokenEndpoint(), KeycloakRealm.AGENT, SECRET_NAME, secrets, grants,
                verifier, audit, clock, Duration.ofSeconds(10));
    }

    private static DataSource dataSource() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic");
        String user = System.getenv().getOrDefault("DB_USER", "postgres");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "postgres");
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static String token(Answer answer) {
        assertEquals(200, answer.status(), () -> "token request: " + answer.body());
        return answer.text("access_token");
    }

    private static DelegationGrant grant(Set<String> scopes) {
        return grants.grant(KeycloakRealm.USER_TENANT, alice, alice, agent, scopes,
                Instant.now().plus(Duration.ofHours(1)), "it-" + System.nanoTime());
    }

    private static ExchangeRequest request(String subjectToken, String audience, Set<String> scopes) {
        return new ExchangeRequest(Credential.of(subjectToken), Credential.of(token(realm.workloadToken())), audience,
                scopes, grant(scopes));
    }

    private static ExchangeException refused(ExchangeRequest request) {
        return assertThrows(ExchangeException.class, () -> exchanger.exchange(request));
    }

    private static Map<String, String> actorFields(String actorToken) {
        return Map.of("actor_token", actorToken, "actor_token_type", "urn:ietf:params:oauth:token-type:access_token");
    }

    // ---- the verifier reads Keycloak's tokens ----------------------------------------------------------------------

    @Test
    void theVerifierAcceptsAUserTokenFromKeycloakAndReadsItsTenantAndScopes() {
        VerifiedToken verified = verifier.verify(token(realm.userToken(KeycloakRealm.API)), KeycloakRealm.API);

        assertEquals(alice, verified.subject(), "sub is Keycloak's user id; no sub_kind means USER");
        assertEquals(KeycloakRealm.USER_TENANT, verified.tenant());
        assertTrue(verified.scopes().containsAll(READ));
        assertTrue(verified.scopes().contains("cases:write"));
        assertFalse(verified.isDelegated());
    }

    @Test
    void theWorkloadTokenOfTheAgentClientIsAnAgentOfItsOwnTenant() {
        // a service account's audience is "account" unless the realm says otherwise; that is all this call needs
        VerifiedToken verified = verifier.verify(token(realm.workloadToken()), "account");

        assertEquals(agent, verified.subject(), "the agent is the service-account user, kind AGENT from its attribute");
        assertEquals(KeycloakRealm.AGENT_TENANT, verified.tenant());
        assertFalse(verified.isDelegated());
    }

    @Test
    void aTokenForAnotherAudienceIsRefusedByTheVerifier() {
        String user = token(realm.userToken(KeycloakRealm.API));
        assertThrows(RuntimeException.class, () -> verifier.verify(user, "tool-nobody"));
    }

    // ---- the exchange through the product's client -----------------------------------------------------------------

    @Test
    void theClientGetsADelegatedTokenOnlyWhereKeycloakRecordsTheActor() {
        ExchangeRequest request = request(token(realm.userToken(KeycloakRealm.API)), TOOL, READ);

        if (delegation) {
            IssuedToken issued = exchanger.exchange(request);
            VerifiedToken verified = verifier.verify(issued.accessToken().reveal(), TOOL);
            assertEquals(Set.of(TOOL), verified.audiences());
            assertEquals(READ, issued.scopes());
            assertEquals(alice, verified.subject(), "the user stays the subject");
            assertEquals(KeycloakRealm.USER_TENANT, verified.tenant());
            assertEquals(List.of(agent.id()), issued.actors(),
                    "act.sub is the user id of the agent's service account, so the grant's agent id must be that id");
            assertTrue(issued.expiresAt().isBefore(Instant.now().plusSeconds(301)));
        } else {
            // Keycloak 26.2 standard exchange ignores actor_token and issues no act claim (docs/identity.md 9.2);
            // the client therefore discards the token. ADR 0021 has to change before this can succeed.
            ExchangeException e = refused(request);
            assertEquals(ExchangeException.Code.BAD_RESPONSE, e.code());
            assertTrue(e.getMessage().contains("actor"), e.getMessage());
        }
    }

    @Test
    void aScopeTheUserNeverHeldIsRefusedByKeycloakAsInvalidScope() {
        ExchangeException e = refused(request(token(realm.userToken(KeycloakRealm.API)), TOOL, Set.of("cases:delete")));

        assertEquals(ExchangeException.Code.REFUSED, e.code());
        assertEquals("invalid_scope", e.oauthError());
    }

    @Test
    void anUnregisteredAudienceIsInvalidClientNotInvalidTarget() {
        ExchangeException e = refused(request(token(realm.userToken(KeycloakRealm.API)), "not-a-client", READ));

        assertEquals(ExchangeException.Code.REFUSED, e.code());
        assertEquals("invalid_client", e.oauthError(), "ADR 0021 and the old test text said invalid_target");
    }

    @Test
    void anExpiredSubjectTokenIsRefusedAsInvalidRequestNotInvalidGrant() throws Exception {
        ExchangeRequest request = request(token(realm.userToken(KeycloakRealm.API_SHORT)), TOOL, READ);
        Thread.sleep(Duration.ofSeconds(KeycloakRealm.SHORT_LIFESPAN + 3L));

        ExchangeException e = refused(request);

        assertEquals(ExchangeException.Code.REFUSED, e.code());
        assertEquals("invalid_request", e.oauthError());
    }

    @Test
    void aSubjectTokenOfALoggedOutSessionIsRefused() {
        Answer login = realm.userToken(KeycloakRealm.API);
        ExchangeRequest request = request(token(login), TOOL, READ);
        realm.logout(KeycloakRealm.API, login.text("refresh_token"));

        ExchangeException e = refused(request);

        assertEquals(ExchangeException.Code.REFUSED, e.code(), "revocation of the user's session is honoured");
    }

    // ---- what the server does with the pieces of the request (raw HTTP) --------------------------------------------

    @Test
    void theActorTokenIsIgnoredByStandardExchangeAndCheckedByDelegation() {
        String user = token(realm.userToken(KeycloakRealm.API));
        String workload = token(realm.workloadToken());
        Map<String, String> asked = new java.util.LinkedHashMap<>(Map.of("audience", TOOL, "scope", "cases:read"));

        Answer garbage = realm.exchange(user, merge(asked, actorFields("not.a.token")));
        Answer ownToken = realm.exchange(user, merge(asked, actorFields(user)));
        Answer proper = realm.exchange(user, merge(asked, actorFields(workload)));

        if (delegation) {
            assertEquals(400, garbage.status());
            assertEquals(400, ownToken.status(), "the actor and the subject must differ");
            assertEquals(200, proper.status(), () -> String.valueOf(proper.body()));
            JsonNode act = KeycloakRealm.payload(proper.text("access_token")).path("act");
            assertEquals(realm.agentUserId, act.path("sub").asText());
            assertEquals(400, realm.exchange(user, asked).status(),
                    "with may_act in the user's token, an exchange without an actor is refused");
        } else {
            assertEquals(200, garbage.status(), "a garbage actor_token is accepted: the server never looks at it");
            assertEquals(200, ownToken.status());
            assertEquals(200, proper.status());
            assertTrue(KeycloakRealm.payload(proper.text("access_token")).path("act").isMissingNode(),
                    "no act claim is ever written by standard exchange");
        }
    }

    @Test
    void theExchangedTokenHasTheScopesAskedForAndALifetimeOf300SecondsEvenForAShortSubjectToken() {
        String shortLived = token(realm.userToken(KeycloakRealm.API_SHORT));
        String workload = token(realm.workloadToken());

        Answer answer = realm.exchange(shortLived, merge(Map.of("audience", TOOL, "scope", "cases:read"),
                actorFields(workload)));

        assertEquals(200, answer.status(), () -> String.valueOf(answer.body()));
        assertFalse(answer.body().has("refresh_token"));
        JsonNode exchanged = KeycloakRealm.payload(answer.text("access_token"));
        JsonNode subject = KeycloakRealm.payload(shortLived);
        assertEquals("cases:read", exchanged.path("scope").asText(), "no profile or email scope leaks in");
        assertEquals(300, exchanged.path("exp").asLong() - exchanged.path("iat").asLong());
        assertEquals(KeycloakRealm.SHORT_LIFESPAN, subject.path("exp").asLong() - subject.path("iat").asLong());
        assertTrue(exchanged.path("exp").asLong() > subject.path("exp").asLong(),
                "KNOWN GAP: Keycloak does not end the exchanged token with the subject token; "
                        + "Rfc8693TokenExchanger caps only by the grant and 300 s");
        assertNotNull(exchanged.path("tenant").asText(null));
        assertEquals(KeycloakRealm.USER_TENANT, exchanged.path("tenant").asText(),
                "the user's tenant, not the agent's, even though the agent client asked");
        assertTrue(exchanged.path("sub_kind").isMissingNode(), "no AGENT kind leaks onto the user's token");
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        Map<String, String> all = new java.util.LinkedHashMap<>(a);
        all.putAll(b);
        return all;
    }
}
