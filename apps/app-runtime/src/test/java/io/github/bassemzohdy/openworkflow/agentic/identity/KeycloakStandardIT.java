package io.github.bassemzohdy.openworkflow.agentic.identity;

import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.READ;
import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.TOOL;
import static io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakHarness.accessToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakRealm.Answer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException.Code;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * WP-102, standard profile: the product's verifier and exchanger against a REAL Keycloak 26.2.5 with only stable
 * features. This is the regression baseline for the observed incompatibility, not a production recommendation
 * (ADR 0021 H1): Keycloak's standard token exchange ignores {@code actor_token} and signs no {@code act} claim, so the
 * product must refuse its answer instead of treating an actorless bearer token as delegated authority.
 *
 * <p>With {@code keycloak.required=true} a missing server is a failure, not a skip ({@link KeycloakSupport}).
 */
class KeycloakStandardIT {

    private static KeycloakHarness h;

    @BeforeAll
    static void server() {
        h = new KeycloakHarness(KeycloakSupport.require("standard"));
    }

    @AfterAll
    static void cleanUp() {
        if (h != null) {
            h.close();
        }
    }

    @Test
    void realIssuerSignatureAndAudienceAreVerified() {
        String user = accessToken(h.realm.userToken());

        VerifiedToken verified = h.verifier.verify(user, KeycloakRealm.AGENT);
        assertEquals(h.alice, verified.subject(), "sub is Keycloak's user id; no sub_kind claim means USER");
        assertEquals(KeycloakRealm.USER_TENANT, verified.tenant());
        assertTrue(verified.scopes().containsAll(Set.of("cases:read", "cases:write")));
        assertFalse(verified.isDelegated());

        VerifiedToken workload = h.verifier.verify(accessToken(h.realm.workloadToken()), KeycloakRealm.AGENT);
        assertEquals(h.agent, workload.subject(), "the agent is its service-account user, kind AGENT from its attribute");
        assertEquals(KeycloakRealm.AGENT_TENANT, workload.tenant());

        // audience: a resource nobody addressed refuses the token
        assertEquals(Reason.AUDIENCE, assertThrows(TokenRejectedException.class,
                () -> h.verifier.verify(user, "tool-nobody")).reason());
        // signature: one changed byte in the signature
        String[] parts = user.split("\\.");
        char[] signature = parts[2].toCharArray();
        int middle = signature.length / 2;
        signature[middle] = signature[middle] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signature);
        assertThrows(TokenRejectedException.class, () -> h.verifier.verify(tampered, KeycloakRealm.AGENT));
        // issuer and key: another realm of the same server signs with another key and names another issuer
        try (KeycloakHarness elsewhere = new KeycloakHarness(h.config)) {
            String foreign = accessToken(elsewhere.realm.userToken());
            assertThrows(TokenRejectedException.class, () -> h.verifier.verify(foreign, KeycloakRealm.AGENT),
                    "a token of another realm is never accepted");
        }
        // time: the same token two hours later (its lifetime is an hour)
        assertEquals(Reason.EXPIRED, assertThrows(TokenRejectedException.class,
                () -> h.verifier(Clock.offset(Clock.systemUTC(), Duration.ofHours(2)))
                        .verify(user, KeycloakRealm.AGENT)).reason());
    }

    @Test
    void actorlessExchangeIsRejected() {
        String user = accessToken(h.realm.userToken());
        DelegationGrant grant = h.grant(Set.of("cases:read", "cases:write"), Duration.ofHours(1));

        // the product refuses what the server answers: a token without act is not delegated authority
        ExchangeException e = assertThrows(ExchangeException.class,
                () -> h.exchanger.exchange(h.request(user, TOOL, READ, grant)));
        assertEquals(Code.BAD_RESPONSE, e.code());
        assertTrue(e.getMessage().contains("actor"), e.getMessage());

        // and it is the product refusing: the very same request is answered 200 with a token that has no act and no
        // refresh token. Nothing in the exchange requires Keycloak to say no.
        Answer raw = h.realm.exchange(user, Map.of("audience", TOOL, "scope", "cases:read",
                "actor_token", accessToken(h.realm.workloadToken()),
                "actor_token_type", "urn:ietf:params:oauth:token-type:access_token"));
        assertEquals(200, raw.status(), () -> String.valueOf(raw.body()));
        JsonNode issued = KeycloakRealm.payload(raw.text("access_token"));
        assertTrue(issued.path("act").isMissingNode(), "standard exchange signs no act claim");
        assertFalse(raw.body().has("refresh_token"));
        assertEquals(300, issued.path("exp").asLong() - issued.path("iat").asLong());

        // the actor_token is not even read: garbage and the user's own token are accepted by the server
        Map<String, String> noActor = Map.of("audience", TOOL, "scope", "cases:read");
        for (String actor : new String[] {"not.a.token", user}) {
            Map<String, String> fields = new java.util.LinkedHashMap<>(noActor);
            fields.put("actor_token", actor);
            fields.put("actor_token_type", "urn:ietf:params:oauth:token-type:access_token");
            assertEquals(200, h.realm.exchange(user, fields).status());
        }
    }

    @Test
    void theServerStillRefusesWhatItCanSee() {
        String user = accessToken(h.realm.userToken());
        DelegationGrant grant = h.grant(Set.of("cases:read", "cases:delete"), Duration.ofHours(1));

        // A scope alice's token never held, though the agent client may use it. Keycloak takes the scopes of the new
        // token from the CLIENT's configuration, not from the subject token, so the server hands out the wider token
        // (KNOWN SERVER GAP, docs/identity.md 9.3); the product refuses before sending: the user's token is the bound.
        Answer rawScope = h.realm.exchange(user, Map.of("audience", TOOL, "scope", "cases:delete"));
        assertEquals(200, rawScope.status(), () -> String.valueOf(rawScope.body()));
        assertTrue(KeycloakRealm.payload(rawScope.text("access_token")).path("scope").asText().contains("cases:delete"));
        ExchangeException scope = assertThrows(ExchangeException.class, () -> h.exchanger.exchange(
                h.request(user, TOOL, Set.of("cases:delete"), grant)));
        assertEquals(Code.SCOPE_WIDENING, scope.code());
        // an audience nobody registered
        ExchangeException audience = assertThrows(ExchangeException.class, () -> h.exchanger.exchange(
                h.request(user, "not-a-client", READ, grant)));
        assertEquals(Code.REFUSED, audience.code());
        // a user session that was ended: the exchange is refused even though the JWT still verifies locally
        String ended = accessToken(h.realm.userToken());
        h.realm.logoutAlice();
        Rfc8693TokenExchanger exchanger = h.exchanger(Clock.systemUTC(), UnaryOperator.identity());
        assertEquals(Code.REFUSED, assertThrows(ExchangeException.class,
                () -> exchanger.exchange(h.request(ended, TOOL, READ, grant))).code());
    }
}
