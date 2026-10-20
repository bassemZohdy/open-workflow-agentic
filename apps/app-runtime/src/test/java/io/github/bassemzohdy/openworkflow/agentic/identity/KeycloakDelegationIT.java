package io.github.bassemzohdy.openworkflow.agentic.identity;

import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.OTHER_TOOL;
import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.READ;
import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.READ_WRITE;
import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.TOOL;
import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.accessToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakRealm.Answer;
import io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakRealm.Login;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException.Code;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JwtTokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ResourceTokenGuard;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * WP-102, delegation profile: the product's exchanger, verifier and resource guard against a REAL Keycloak 26.8 with
 * the native delegation preview ({@code token-exchange-delegation}, {@code parameterized-scopes}). ADR 0021 H1 chooses
 * native, issuer-signed actor proof: the user's authority for the actor comes from the server's own permission and
 * consent, not from a mapper that writes a fixed claim, and the exchanged token carries the actor in {@code act}.
 *
 * <p>With {@code keycloak.required=true} a missing server is a failure, not a skip ({@link KeycloakSupport}). Run
 * instructions: docs/identity.md section 9.4. Nothing here is a characterization that passes by asserting a gap: where
 * the server does not enforce what the product needs (the exchanged token's lifetime is not bounded by the subject
 * token or the grant), the test asserts that the PRODUCT refuses the overlong credential.
 */
class KeycloakDelegationIT {

    private static KeycloakHarness h;

    @BeforeAll
    static void server() {
        h = new KeycloakHarness(KeycloakSupport.require("delegation"));
    }

    @AfterAll
    static void cleanUp() {
        if (h != null) {
            h.close();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ExchangeException refused(Rfc8693TokenExchanger exchanger, ExchangeRequest request) {
        return assertThrows(ExchangeException.class, () -> exchanger.exchange(request));
    }

    private static Reason rejected(ResourceTokenGuard guard, String token, Set<String> scopes) {
        return assertThrows(TokenRejectedException.class, () -> guard.admit(token, scopes)).reason();
    }

    private static Map<String, String> actorFields(String actorToken) {
        return Map.of("actor_token", actorToken, "actor_token_type", "urn:ietf:params:oauth:token-type:access_token");
    }

    private static Map<String, String> asked(String audience, String scope) {
        return Map.of("audience", audience, "scope", scope);
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        Map<String, String> all = new java.util.LinkedHashMap<>(a);
        all.putAll(b);
        return all;
    }

    // ---- the required cases ----------------------------------------------------------------------------------------

    @Test
    void nativeDelegationPreservesAuthorityBounds() {
        String subject = h.delegatedSubject(KeycloakRealm.CONSOLE);
        JsonNode mayAct = KeycloakRealm.payload(subject).path("may_act");
        assertEquals(h.agent.id(), mayAct.path("sub").asText(),
                "the server wrote may_act from its own permission and consent: the service-account user id");
        DelegationGrant grant = h.grant(READ_WRITE, Duration.ofHours(1));

        IssuedToken issued = h.exchanger.exchange(h.request(subject, TOOL, READ, grant));

        VerifiedToken verified = h.verifier.verify(issued.accessToken().reveal(), TOOL);
        assertEquals(h.alice, verified.subject(), "the user stays the subject");
        assertEquals(KeycloakRealm.USER_TENANT, verified.tenant(), "the user's tenant, not the agent's");
        assertEquals(List.of(h.agent.id()), verified.actors(), "act.sub is the agent's service-account user id");
        assertEquals(Set.of(TOOL), verified.audiences(), "one audience");
        assertEquals(READ, issued.scopes(), "exactly the scopes asked for, nothing widened");
        assertNotNull(verified.tokenId());
        Instant now = Instant.now();
        assertTrue(issued.expiresAt().isAfter(now));
        assertTrue(issued.expiresAt().isBefore(now.plusSeconds(301)), "at most five minutes");
        assertFalse(issued.expiresAt().isAfter(grant.expiresAt()));

        // the resource boundary: scope, audience binding, active grant and one use only
        ResourceTokenGuard guard = h.guard(TOOL);
        assertEquals(h.agent.id(), guard.admit(issued.accessToken().reveal(), READ).actor());
        assertEquals(Reason.REPLAYED, rejected(guard, issued.accessToken().reveal(), READ), "a second use is a replay");
        IssuedToken second = h.exchanger.exchange(h.request(subject, TOOL, READ, grant));
        assertEquals(Reason.AUDIENCE, rejected(h.guard(OTHER_TOOL), second.accessToken().reveal(), READ),
                "another resource does not accept a token for this one");
        assertEquals(Reason.SCOPE, rejected(h.guard(TOOL), second.accessToken().reveal(), READ_WRITE),
                "the token has no scope it was not asked for");

        // what the product refuses before anything is sent
        DelegationGrant readOnly = h.grant(READ, Duration.ofHours(1));
        ExchangeException widening = refused(h.exchanger, h.request(subject, TOOL, READ_WRITE, readOnly));
        assertEquals(Code.SCOPE_WIDENING, widening.code(), "a scope the grant does not hold");
        DelegationGrant otherTenant = h.grant("tenant-other", h.agent, READ, Duration.ofHours(1));
        assertEquals(Code.INPUT_REJECTED, refused(h.exchanger, h.request(subject, TOOL, READ, otherTenant)).code(),
                "the subject token is of another tenant than the grant");
        // and what the server refuses
        assertEquals(Code.REFUSED, refused(h.exchanger, h.request(subject, "not-a-client", READ, grant)).code(),
                "an audience nobody registered");

        // A scope the user's own token never held, though a grant holds it. Keycloak's native delegation takes the
        // scopes of the new token from the actor client's configuration, so the SERVER hands out the wider token
        // (raw request below); the product refuses before sending, because the user's token is the bound.
        DelegationGrant held = h.grant(Set.of("cases:read", "cases:delete"), Duration.ofHours(1));
        Set<String> delete = Set.of("cases:delete");
        assertFalse(KeycloakRealm.payload(subject).path("scope").asText().contains("cases:delete"));
        Answer wider = h.realm.exchange(subject, merge(asked(TOOL, "cases:delete"),
                actorFields(accessToken(h.realm.workloadToken()))));
        assertEquals(200, wider.status(), "KNOWN SERVER GAP (docs/identity.md 9.3): " + wider.body());
        assertTrue(KeycloakRealm.payload(wider.text("access_token")).path("scope").asText().contains("cases:delete"));
        ExchangeException scope = refused(h.exchanger, h.request(subject, TOOL, delete, held));
        assertEquals(Code.SCOPE_WIDENING, scope.code(), "the product never widens beyond the user's token");
    }

    @Test
    void invalidActorIsRejected() {
        String subject = h.delegatedSubject(KeycloakRealm.CONSOLE);
        DelegationGrant grant = h.grant(READ, Duration.ofHours(1));
        Credential other = Credential.of(accessToken(h.realm.otherWorkloadToken()));

        // another agent's valid workload token for the grant of the runtime: refused before anything is sent
        ExchangeRequest wrongActor = new ExchangeRequest(Credential.of(subject), other, TOOL, READ, grant);
        assertEquals(Code.INPUT_REJECTED, refused(h.exchanger, wrongActor).code());
        // a token that is not a token
        ExchangeRequest garbage = new ExchangeRequest(Credential.of(subject), Credential.of("not.a.token"), TOOL, READ,
                grant);
        assertEquals(Code.INPUT_REJECTED, refused(h.exchanger, garbage).code());
        // the user's own token as the actor: it is the user, not the grant's agent
        ExchangeRequest userAsActor = new ExchangeRequest(Credential.of(subject), Credential.of(subject), TOOL, READ,
                grant);
        assertEquals(Code.INPUT_REJECTED, refused(h.exchanger, userAsActor).code());

        // the other agent holds a grant of its own, but alice's consent named the runtime, not the other agent: the
        // product lets it through (identity and grant match) and the SERVER refuses, with no token as the result
        DelegationGrant othersGrant = h.grant(KeycloakRealm.USER_TENANT, h.otherAgent, READ, Duration.ofHours(1));
        ExchangeRequest notConsented = new ExchangeRequest(Credential.of(subject), other, TOOL, READ, othersGrant);
        assertEquals(Code.REFUSED, refused(h.exchanger, notConsented).code());

        // raw: the server itself checks the actor against may_act
        String workload = accessToken(h.realm.workloadToken());
        Map<String, String> toolRead = asked(TOOL, "cases:read");
        assertEquals(400, h.realm.exchange(subject, merge(toolRead, actorFields("not.a.token"))).status());
        assertEquals(400, h.realm.exchange(subject, merge(toolRead, actorFields(subject))).status(),
                "subject and actor must differ");
        assertEquals(400, h.realm.exchange(subject, merge(toolRead,
                actorFields(accessToken(h.realm.otherWorkloadToken())))).status(), "may_act names another actor");
        assertEquals(200, h.realm.exchange(subject, merge(toolRead, actorFields(workload))).status(),
                "the same request with the consented actor succeeds: the refusals above are about the actor");
    }

    @Test
    void revokedOrExpiredGrantIsRejected() throws Exception {
        String subject = h.delegatedSubject(KeycloakRealm.CONSOLE);

        // revoked before the exchange: nothing is sent
        DelegationGrant revoked = h.grant(READ, Duration.ofHours(1));
        h.grants.revoke(revoked.id(), h.alice, "alice withdrew", null);
        ExchangeException before = refused(h.exchanger, h.request(subject, TOOL, READ, revoked));
        assertEquals(Code.GRANT_INACTIVE, before.code());

        // revoked after the token was issued: the resource stops an unexpired token. The token is bound to the exact grant
        // that issued it (identity.issued_token_binding), so an EQUIVALENT grant that stays active must not revive it
        DelegationGrant later = h.grant(READ, Duration.ofHours(1));
        DelegationGrant equivalent = h.grant(READ, Duration.ofHours(1));
        IssuedToken live = h.exchanger.exchange(h.request(subject, TOOL, READ, later));
        assertEquals(later.id(), h.bindings.find(live.issuer(), live.tokenId()).orElseThrow().grantId());
        h.grants.revoke(later.id(), h.alice, "alice withdrew later", null);
        assertTrue(h.grants.check(equivalent.id()).active(), "an equivalent grant is still active");
        assertEquals(Reason.GRANT_INACTIVE, rejected(h.guard(TOOL), live.accessToken().reveal(), READ),
                "provider-side validity and an equivalent grant are not a substitute for the issuing grant");

        // revoked while the request is in flight: the token the server answered with is discarded
        DelegationGrant racing = h.grant(READ, Duration.ofHours(1));
        UnaryOperator<TokenVerifier> revokeWhenTheAnswerIsVerified = real -> (token, audience) -> {
            VerifiedToken verified = real.verify(token, audience);
            if (audience.equals(TOOL)) {
                h.grants.revoke(racing.id(), h.alice, "revoked in flight", null);
            }
            return verified;
        };
        Rfc8693TokenExchanger inFlight = h.exchanger(Clock.systemUTC(), revokeWhenTheAnswerIsVerified);
        assertEquals(Code.GRANT_INACTIVE, refused(inFlight, h.request(subject, TOOL, READ, racing)).code());

        // expired: nothing is issued under a grant that has ended
        DelegationGrant brief = h.grant(READ, Duration.ofSeconds(3));
        Thread.sleep(Duration.ofSeconds(4));
        assertEquals(Code.GRANT_INACTIVE, refused(h.exchanger, h.request(subject, TOOL, READ, brief)).code());
    }

    /**
     * KNOWN INCOMPATIBILITY (open ADR decision, docs/handoffs/claude/WP-102.md section 8). Keycloak accepts the actor token
     * only from the exchanging client itself (it refuses "Requesting client does not match the client_id in the may_act
     * claim"), and it gives a token the lifespan of its client at the moment of issue. The actor token and the token issued
     * by the exchange therefore have the same lifespan, and the exchanged one is issued later: with the strict H3 bound
     * (nothing may outlive the verified actor token) a stock Keycloak exchange succeeds only when it happens within the
     * second the actor token was issued. This test asserts the product's refusal deterministically; it does not make the
     * positive case pass.
     */
    @Test
    void anActorTokenOfTheExchangingClientExpiresBeforeWhatIsIssuedLaterSoTheStrictBoundRefuses() throws Exception {
        String subject = h.delegatedSubject(KeycloakRealm.CONSOLE);
        DelegationGrant grant = h.grant(READ, Duration.ofHours(1));
        ExchangeRequest request = h.request(subject, TOOL, READ, grant); // the actor token is issued here
        Thread.sleep(Duration.ofSeconds(2));

        ExchangeException e = refused(h.exchanger, request);

        assertEquals(Code.BAD_RESPONSE, e.code());
        assertTrue(e.getMessage().contains("actor"), e.getMessage());
    }

    @Test
    void subjectExpiryBoundsIssuedCredential() {
        // a subject token that lives an hour: the issued credential ends within five minutes, so within it
        String longLived = h.delegatedSubject(KeycloakRealm.CONSOLE);
        DelegationGrant grant = h.grant(READ, Duration.ofHours(1));
        IssuedToken issued = h.exchanger.exchange(h.request(longLived, TOOL, READ, grant));
        long subjectExp = KeycloakRealm.payload(longLived).path("exp").asLong();
        assertTrue(issued.expiresAt().getEpochSecond() <= subjectExp);

        // a subject token with 100 seconds left: the server still signs a 300 second token (it does not look at the
        // subject's expiry), and the product refuses that overlong bearer instead of shortening its own metadata
        String shortLived = h.delegatedSubject(KeycloakRealm.CONSOLE_SHORT);
        String workload = accessToken(h.realm.workloadToken());
        Answer rawAnswer = h.realm.exchange(shortLived, merge(asked(TOOL, "cases:read"), actorFields(workload)));
        assertEquals(200, rawAnswer.status(), () -> String.valueOf(rawAnswer.body()));
        JsonNode raw = KeycloakRealm.payload(rawAnswer.text("access_token"));
        assertTrue(raw.path("exp").asLong() > KeycloakRealm.payload(shortLived).path("exp").asLong(),
                "the server's token outlives the subject token that authorized it");
        ExchangeException overlong = refused(h.exchanger, h.request(shortLived, TOOL, READ, grant));
        assertEquals(Code.BAD_RESPONSE, overlong.code());
        assertTrue(overlong.getMessage().contains("subject"), overlong.getMessage());

        // the same for a grant that ends sooner than the credential the server would issue
        DelegationGrant brief = h.grant(READ, Duration.ofSeconds(60));
        ExchangeException outlivesGrant = refused(h.exchanger, h.request(longLived, TOOL, READ, brief));
        assertEquals(Code.BAD_RESPONSE, outlivesGrant.code());
        assertTrue(outlivesGrant.getMessage().contains("grant"), outlivesGrant.getMessage());

        // an expired subject token is refused before anything is sent
        Rfc8693TokenExchanger twoHoursLater = h.exchanger(Clock.offset(Clock.systemUTC(), Duration.ofHours(2)),
                UnaryOperator.identity());
        assertEquals(Code.INPUT_REJECTED, refused(twoHoursLater, h.request(longLived, TOOL, READ, grant)).code());
    }

    @Test
    void issuerKeyRotationIsHandled() throws Exception {
        try (KeycloakHarness own = new KeycloakHarness(h.config)) {
            String before = accessToken(own.realm.userToken());
            JwtTokenVerifier verifier = own.verifier(Clock.systemUTC());
            verifier.verify(before, KeycloakRealm.AGENT); // the key set is cached now

            own.realm.rotateKeys();
            String after = accessToken(own.realm.userToken());
            assertNotEquals(kid(before), kid(after), "the realm signs with a new key");

            // the new key may be unknown until the key set is refetched (rate limited): the answer is a refusal,
            // never an acceptance of a token whose key is not known
            Instant deadline = Instant.now().plusSeconds(75);
            boolean accepted = false;
            while (!accepted && Instant.now().isBefore(deadline)) {
                try {
                    verifier.verify(after, KeycloakRealm.AGENT);
                    accepted = true;
                } catch (TokenRejectedException e) {
                    assertTrue(e.reason() == Reason.SIGNATURE || e.reason() == Reason.VERIFIER_UNAVAILABLE,
                            "an unknown key is refused fail-closed, not " + e.reason());
                    Thread.sleep(Duration.ofSeconds(3));
                }
            }
            assertTrue(accepted, "the verifier picks up the rotated key");
            // the retired key is gone from the refreshed key set: its tokens are refused, first fail-closed while the
            // unknown-key refetch is rate limited, then for their signature
            Reason retired = null;
            deadline = Instant.now().plusSeconds(75);
            while (retired != Reason.SIGNATURE && Instant.now().isBefore(deadline)) {
                retired = assertThrows(TokenRejectedException.class, () -> verifier.verify(before, KeycloakRealm.AGENT),
                        "a token of the retired key is never accepted").reason();
                if (retired != Reason.SIGNATURE) {
                    assertEquals(Reason.VERIFIER_UNAVAILABLE, retired);
                    Thread.sleep(Duration.ofSeconds(3));
                }
            }
            assertEquals(Reason.SIGNATURE, retired, "the retired key no longer verifies its tokens");

            // an issuer whose key set cannot be fetched is never an acceptance
            JwtTokenVerifier unreachable = JwtTokenVerifier.forJwks(own.realm.issuer(), "http://127.0.0.1:1/certs",
                    Clock.systemUTC(), Duration.ofSeconds(30), "tenant", "sub_kind", Duration.ofSeconds(2));
            assertEquals(Reason.VERIFIER_UNAVAILABLE, assertThrows(TokenRejectedException.class,
                    () -> unreachable.verify(after, KeycloakRealm.AGENT)).reason());

            // and the whole delegation path works with the rotated key
            String subject = own.delegatedSubject(KeycloakRealm.CONSOLE);
            DelegationGrant grant = own.grant(READ, Duration.ofHours(1));
            IssuedToken issued = own.exchanger.exchange(own.request(subject, TOOL, READ, grant));
            assertEquals(own.agent.id(), own.guard(TOOL).admit(issued.accessToken().reveal(), READ).actor());
        }
    }

    @Test
    void delegationRequiresPermissionAndConsent() {
        // consent refused: the server issues no code and so no token with authority for the actor
        Login refusedConsent = h.realm.delegatedLogin(KeycloakRealm.CONSOLE, KeycloakRealm.AGENT, false);
        assertTrue(refusedConsent.consentShown(), "the delegation scope always asks the user");
        assertEquals("access_denied", refusedConsent.error());
        assertNull(refusedConsent.token());

        // no permission for this actor: the scope is silently dropped, the login succeeds without may_act
        Login noPermission = h.realm.delegatedLogin(KeycloakRealm.CONSOLE, KeycloakRealm.OTHER_AGENT, true);
        String withoutMayAct = accessToken(noPermission.token());
        assertTrue(KeycloakRealm.payload(withoutMayAct).path("may_act").isMissingNode(),
                "no delegate permission, no may_act");
        DelegationGrant othersGrant = h.grant(KeycloakRealm.USER_TENANT, h.otherAgent, READ, Duration.ofHours(1));
        ExchangeRequest notAllowed = new ExchangeRequest(Credential.of(withoutMayAct),
                Credential.of(accessToken(h.realm.otherWorkloadToken())), TOOL, READ, othersGrant);
        assertEquals(Code.REFUSED, refused(h.exchanger, notAllowed).code(),
                "a platform grant alone does not make the server accept an actor it was never told to trust");

        // a login that never went through consent carries no may_act either, whatever the platform grant says
        String passwordToken = accessToken(h.realm.userToken());
        assertTrue(KeycloakRealm.payload(passwordToken).path("may_act").isMissingNode());
        DelegationGrant grant = h.grant(READ, Duration.ofHours(1));
        assertEquals(Code.REFUSED, refused(h.exchanger, h.request(passwordToken, TOOL, READ, grant)).code());
        assertEquals(400, h.realm.exchange(passwordToken, merge(asked(TOOL, "cases:read"),
                actorFields(accessToken(h.realm.workloadToken())))).status(), "raw: actor given, no may_act");

        // with the permission and the user's consent it works, and the token says who acts
        Login consented = h.realm.delegatedLogin(KeycloakRealm.CONSOLE, KeycloakRealm.AGENT, true);
        assertTrue(consented.consentShown());
        String subject = accessToken(consented.token());
        IssuedToken issued = h.exchanger.exchange(h.request(subject, TOOL, READ, grant));
        assertEquals(List.of(h.agent.id()), issued.actors());
        // and a delegation-bearing subject token cannot be used without naming the actor
        assertEquals(400, h.realm.exchange(subject, asked(TOOL, "cases:read")).status(),
                "with may_act in the subject token, an exchange without an actor is refused");
    }

    private static String kid(String jwt) {
        try {
            String header = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[0]),
                    java.nio.charset.StandardCharsets.UTF_8);
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(header).path("kid").asText();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
