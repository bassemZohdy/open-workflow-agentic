package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.ALICE;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_ID;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_SECRET_NAME;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.PLANNER;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException.Code;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GrantCheck;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.DelegationAuthorizationServer.Fault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The RFC 8693 client against the in-process authorization server ({@link DelegationAuthorizationServer}). This proves
 * what OUR client does: what it sends, what it refuses, and that a misbehaving server cannot make it hand out a token
 * that breaks the rules. It does not prove what Keycloak does; docs/identity.md section 9 describes that test.
 */
class Rfc8693TokenExchangerTest {

    private static final String AUDIENCE = "tool-cases";
    private static final Set<String> SCOPES = Set.of("cases:read", "cases:write");

    private IdentityFixture fx;
    private Rfc8693TokenExchanger exchanger;
    private DelegationGrant grant;
    private String userToken;
    private String workload;

    @BeforeEach
    void start() throws Exception {
        fx = new IdentityFixture(AUDIENCE, "tool-search", CLIENT_ID);
        exchanger = exchanger();
        grant = fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, SCOPES, fx.clock.instant().plus(Duration.ofHours(1)),
                "corr-1");
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofMinutes(30));
        workload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
    }

    @AfterEach
    void stop() {
        fx.close();
    }

    private void freshTokens() throws Exception {
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofMinutes(30));
        workload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
    }

    private Rfc8693TokenExchanger exchanger() {
        return new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets, fx.grants, fx.bindings,
                fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2));
    }

    private ExchangeRequest request(Set<String> scopes) {
        return new ExchangeRequest(Credential.of(userToken), Credential.of(workload), AUDIENCE, scopes, grant);
    }

    private ExchangeException refused(ExchangeRequest request) {
        return assertThrows(ExchangeException.class, () -> exchanger.exchange(request));
    }

    // ---- the good path -----------------------------------------------------------------------------------------

    @Test
    void issuesOneShortLivedTokenForTheAudienceAskedWithTheScopesAsked() throws Exception {
        IssuedToken token = exchanger.exchange(request(Set.of("cases:read")));

        assertEquals(AUDIENCE, token.audience());
        assertEquals(Set.of("cases:read"), token.scopes());
        assertEquals(grant.id(), token.grantId());
        assertEquals(List.of("planner"), token.actors());
        assertTrue(token.usableAt(fx.clock.instant()));
        assertFalse(token.expiresAt().isAfter(fx.clock.instant().plusSeconds(TokenExchanger.MAX_LIFETIME_SECONDS)));
        assertNotNull(token.tokenId());

        VerifiedToken verified = fx.verifier.verify(token.accessToken().reveal(), AUDIENCE);
        assertEquals("alice", verified.subject().id(), "the token is for the user, acting through the agent");
        assertEquals("planner", verified.actor());
        assertFalse(token.accessToken().reveal().equals(userToken), "no passthrough of the user's token");
        assertFalse(token.accessToken().reveal().equals(workload));
    }

    @Test
    void theRequestFollowsRfc8693AndAuthenticatesTheClientWithItsSecret() {
        exchanger.exchange(request(Set.of("cases:write", "cases:read")));

        DelegationAuthorizationServer.Received received = fx.as.received().get(0);
        assertEquals(DelegationAuthorizationServer.TOKEN_EXCHANGE, received.form().get("grant_type"));
        assertEquals(userToken, received.form().get("subject_token"));
        assertEquals(workload, received.form().get("actor_token"));
        assertEquals(DelegationAuthorizationServer.ACCESS_TOKEN_TYPE, received.form().get("subject_token_type"));
        assertEquals(DelegationAuthorizationServer.ACCESS_TOKEN_TYPE, received.form().get("actor_token_type"));
        assertEquals(AUDIENCE, received.form().get("audience"));
        assertEquals("cases:read cases:write", received.form().get("scope"));
        assertFalse(received.form().containsKey("client_secret"), "the secret goes in the Authorization header only");
        assertTrue(received.authorization().startsWith("Basic "));
    }

    @Test
    void anAgentToAgentDelegationRecordsTheWholeChainInTheToken() throws Exception {
        // the parent hop is addressed to the runtime that delegates further: input tokens are verified for the client
        IssuedToken first = exchanger.exchange(new ExchangeRequest(Credential.of(userToken), Credential.of(workload),
                CLIENT_ID, Set.of("cases:read", "cases:write"), grant));
        DelegationGrant child = fx.grants.delegate(grant.id(), PLANNER, WORKER, Set.of("cases:read"),
                fx.clock.instant().plus(Duration.ofMinutes(30)), "corr-2");
        String workerWorkload = fx.as.workloadToken("worker", fx.tenant, Duration.ofMinutes(30));

        IssuedToken second = exchanger.exchange(new ExchangeRequest(first.accessToken(),
                Credential.of(workerWorkload), "tool-search", Set.of("cases:read"), child));

        assertEquals(List.of("worker", "planner"), second.actors(), "nearest actor first");
        assertEquals(Set.of("cases:read"), second.scopes());
    }

    // ---- nothing is sent when the grant says no ----------------------------------------------------------------

    @Test
    void aRevokedGrantSendsNothingAndSaysWhy() {
        fx.grants.revoke(grant.id(), ALICE, "user withdrew", null);

        ExchangeException e = refused(request(Set.of("cases:read")));

        assertEquals(Code.GRANT_INACTIVE, e.code());
        assertEquals(GrantCheck.Reason.REVOKED, e.grantReason());
        assertEquals(0, fx.as.requestCount(), "no request reaches the authorization server");
    }

    @Test
    void anExpiredGrantSendsNothing() {
        fx.clock.advance(Duration.ofHours(1));

        ExchangeException e = refused(request(Set.of("cases:read")));

        assertEquals(Code.GRANT_INACTIVE, e.code());
        assertEquals(GrantCheck.Reason.EXPIRED, e.grantReason());
        assertEquals(0, fx.as.requestCount());
    }

    @Test
    void aRevokedParentEndsTheChildToo() throws Exception {
        DelegationGrant child = fx.grants.delegate(grant.id(), PLANNER, WORKER, Set.of("cases:read"),
                fx.clock.instant().plus(Duration.ofMinutes(30)), null);
        fx.grants.revoke(grant.id(), ALICE, "stop", null);

        ExchangeException e = refused(new ExchangeRequest(Credential.of(userToken), Credential.of(workload), AUDIENCE,
                Set.of("cases:read"), child));

        assertEquals(Code.GRANT_INACTIVE, e.code());
        assertEquals(0, fx.as.requestCount());
    }

    @Test
    void scopesTheGrantDoesNotHoldAreRefusedBeforeAnyRequest() {
        ExchangeException e = refused(request(Set.of("cases:read", "cases:delete")));

        assertEquals(Code.SCOPE_WIDENING, e.code());
        assertEquals(0, fx.as.requestCount());
    }

    @Test
    void aRequestWithoutScopesOrWithABadAudienceIsRefusedAtConstruction() {
        assertEquals(Code.INVALID_REQUEST, assertThrows(ExchangeException.class, () -> request(Set.of())).code());
        assertEquals(Code.INVALID_REQUEST, assertThrows(ExchangeException.class, () -> new ExchangeRequest(
                Credential.of(userToken), Credential.of(workload), "bad audience!", SCOPES, grant)).code());
        assertEquals(Code.INVALID_REQUEST, assertThrows(ExchangeException.class, () -> request(Set.of("Bad Scope")))
                .code());
    }

    // ---- the grant expiry is ours, so the exchanger enforces it ---------------------------------------------------

    @Test
    void aTokenThatWouldOutliveTheGrantIsDiscardedBecauseTheServerCannotKnowTheGrantExpiry() throws Exception {
        fx.clock.advance(Duration.ofMinutes(58));   // the grant has 120 s left; the server issues 300 s
        freshTokens();

        ExchangeException e = refused(request(Set.of("cases:read")));

        assertEquals(Code.BAD_RESPONSE, e.code());
        assertTrue(e.getMessage().contains("outlives"));
    }

    @Test
    void aTokenThatEndsBeforeTheGrantIsAccepted() throws Exception {
        fx.clock.advance(Duration.ofMinutes(58));
        freshTokens();
        fx.as.lifetime(Duration.ofSeconds(60));
        IssuedToken token = exchanger.exchange(request(Set.of("cases:read")));

        assertFalse(token.expiresAt().isAfter(grant.expiresAt()));
    }

    // ---- a server that breaks its contract cannot make us issue a bad token --------------------------------------

    private void assertBadResponse(Fault fault) {
        fx.as.fault(fault);
        ExchangeException e = refused(request(Set.of("cases:read")));
        assertEquals(Code.BAD_RESPONSE, e.code(), fault.name());
        assertFalse(String.valueOf(e.getMessage()).contains(userToken), "no token in the message");
        assertFalse(String.valueOf(e.getMessage()).contains("refresh-token"), "no token in the message");
        assertEquals(1, fx.sink.byAction(Rfc8693TokenExchanger.REFUSED).size(), "the refusal is audited");
        assertTrue(fx.sink.byAction(Rfc8693TokenExchanger.ISSUED).isEmpty(), "nothing was issued");
    }

    @Test
    void aRefreshTokenIsRefused() {
        assertBadResponse(Fault.REFRESH_TOKEN);
    }

    @Test
    void aLongLivedTokenIsRefused() {
        fx.as.lifetime(Duration.ofHours(1));
        fx.as.fault(Fault.IGNORE_SUBJECT_EXPIRY);
        ExchangeException e = refused(request(Set.of("cases:read")));
        assertEquals(Code.BAD_RESPONSE, e.code());
        assertTrue(e.getMessage().contains("300"));
    }

    @Test
    void aTokenWithMoreScopesThanAskedIsRefused() {
        assertBadResponse(Fault.WIDER_SCOPE);
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() {
        assertBadResponse(Fault.WRONG_AUDIENCE);
    }

    @Test
    void aTokenForAnotherSubjectIsRefused() {
        assertBadResponse(Fault.OTHER_SUBJECT);
    }

    @Test
    void aTokenWithoutAnActorIsRefused() {
        assertBadResponse(Fault.NO_ACTOR);
    }

    @Test
    void thePassedThroughSubjectTokenIsRefused() {
        assertBadResponse(Fault.ECHO_SUBJECT_TOKEN);
    }

    @Test
    void anExpiresInLongerThanTheLimitIsRefused() {
        assertBadResponse(Fault.LYING_EXPIRES_IN);
    }

    @Test
    void aBodyThatIsNotJsonOrIsTooLargeIsRefused() {
        fx.as.fault(Fault.NOT_JSON);
        assertEquals(Code.BAD_RESPONSE, refused(request(Set.of("cases:read"))).code());
        fx.as.fault(Fault.HUGE_BODY);
        assertEquals(Code.BAD_RESPONSE, refused(request(Set.of("cases:read"))).code());
    }

    @Test
    void aServerOutageIsUnavailableNotAToken() {
        fx.as.fault(Fault.UNAVAILABLE);

        assertEquals(Code.UNAVAILABLE, refused(request(Set.of("cases:read"))).code());
    }

    @Test
    void aServerThatIsNotListeningIsUnavailable() {
        Rfc8693TokenExchanger dead = new Rfc8693TokenExchanger("http://127.0.0.1:1/token", CLIENT_ID,
                CLIENT_SECRET_NAME, fx.secrets, fx.grants, fx.bindings, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(1));

        assertEquals(Code.UNAVAILABLE, assertThrows(ExchangeException.class, () -> dead.exchange(request(
                Set.of("cases:read")))).code());
    }

    // ---- the server says no ------------------------------------------------------------------------------------

    @Test
    void anAudienceThatIsNotRegisteredIsRefusedByTheServerWithItsOAuthError() {
        ExchangeException e = refused(new ExchangeRequest(Credential.of(userToken), Credential.of(workload),
                "tool-unregistered", Set.of("cases:read"), grant));

        assertEquals(Code.REFUSED, e.code());
        assertEquals("invalid_target", e.oauthError());
    }

    @Test
    void aScopeTheUsersOwnTokenLacksIsRefusedBeforeAnythingIsSentEvenThoughTheGrantHoldsIt() throws Exception {
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", Set.of("cases:read"), Duration.ofMinutes(30));

        ExchangeException e = refused(request(Set.of("cases:write")));

        assertEquals(Code.SCOPE_WIDENING, e.code());
        assertTrue(fx.as.received().isEmpty(), "the product bounds the scopes itself; it does not rely on the server");
    }

    @Test
    void anExpiredSubjectTokenIsRefusedBeforeAnythingIsSent() throws Exception {
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofSeconds(10));
        workload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
        fx.clock.advance(Duration.ofSeconds(41)); // past exp plus the 30 s skew: the verifier refuses it

        ExchangeException e = refused(request(Set.of("cases:read")));

        assertEquals(Code.INPUT_REJECTED, e.code());
        assertTrue(fx.as.received().isEmpty(), "an expired subject token is never sent to the server");
    }

    @Test
    void aSubjectTokenOfAnotherUserTenantOrChainIsRefusedBeforeAnythingIsSent() throws Exception {
        String otherUser = fx.as.userToken("mallory", fx.tenant, "oa-api", SCOPES, Duration.ofMinutes(30));
        String otherTenant = fx.as.userToken("alice", "tenant-other", "oa-api", SCOPES, Duration.ofMinutes(30));

        userToken = otherUser;
        assertEquals(Code.INPUT_REJECTED, refused(request(Set.of("cases:read"))).code());
        userToken = otherTenant;
        assertEquals(Code.INPUT_REJECTED, refused(request(Set.of("cases:read"))).code());
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofMinutes(30));
        workload = fx.as.workloadToken("worker", fx.tenant, Duration.ofMinutes(30)); // not the grant's agent
        assertEquals(Code.INPUT_REJECTED, refused(request(Set.of("cases:read"))).code());
        workload = "not-a-token";
        assertEquals(Code.INPUT_REJECTED, refused(request(Set.of("cases:read"))).code());

        assertTrue(fx.as.received().isEmpty(), "nothing reached the server");
    }

    @Test
    void aGrantRevokedWhileTheRequestIsInFlightIssuesNothing() throws Exception {
        TokenVerifier revokeWhenTheAnswerIsVerified = (token, audience) -> {
            VerifiedToken verified = fx.verifier.verify(token, audience);
            if (audience.equals(AUDIENCE)) {
                fx.grants.revoke(grant.id(), ALICE, "revoked in flight", null);
            }
            return verified;
        };
        Rfc8693TokenExchanger racing = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID, CLIENT_SECRET_NAME,
                fx.secrets, fx.grants, fx.bindings, revokeWhenTheAnswerIsVerified, fx.audit(), fx.clock, Duration.ofSeconds(2));

        ExchangeException e = assertThrows(ExchangeException.class, () -> racing.exchange(request(SCOPES)));

        assertEquals(Code.GRANT_INACTIVE, e.code());
        assertEquals(1, fx.as.received().size(), "the request was sent; the token is discarded after the answer");
    }

    @Test
    void anIssuedTokenThatOutlivesTheSubjectTokenIsRefusedNotShortened() throws Exception {
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofSeconds(100));
        fx.as.fault(DelegationAuthorizationServer.Fault.IGNORE_SUBJECT_EXPIRY); // the server does not end its token with the subject token

        ExchangeException e = refused(request(Set.of("cases:read")));

        assertEquals(Code.BAD_RESPONSE, e.code());
        assertTrue(e.getMessage().contains("subject"), e.getMessage());
    }

    // ---- actor freshness (ADR 0021 H3 freshness amendment; replaces the superseded actor-output-exp refusal) ----------

    private String actorWith(java.util.function.Consumer<Map<String, Object>> change) throws Exception {
        Map<String, Object> claims = fx.idp.baseClaims("planner", CLIENT_ID, Duration.ofMinutes(30));
        claims.put("sub_kind", "AGENT");
        claims.put("tenant", fx.tenant);
        change.accept(claims);
        return fx.idp.signClaims(claims);
    }

    @Test
    void aFreshActorWhoseTokenEndsBeforeTheIssuedOneStillGetsTheSignedCredential() throws Exception {
        workload = fx.as.workloadToken("planner", fx.tenant, Duration.ofSeconds(60));
        fx.as.fault(DelegationAuthorizationServer.Fault.IGNORE_SUBJECT_EXPIRY); // the server issues its full lifetime

        IssuedToken token = exchanger.exchange(request(Set.of("cases:read")));

        // the credential carries its own signed lifetime and outlives the workload proof; subject/grant still bound it
        assertTrue(token.expiresAt().isAfter(fx.clock.instant().plusSeconds(60)));
        assertTrue(Duration.between(fx.clock.instant(), token.expiresAt()).compareTo(Duration.ofSeconds(300)) <= 0);
    }

    @Test
    void aMissingStaleFutureOrExpiredActorIsRefusedBeforeAnythingIsSent() throws Exception {
        long now = fx.clock.instant().getEpochSecond();
        Map<String, java.util.function.Consumer<Map<String, Object>>> cases = new java.util.LinkedHashMap<>();
        cases.put("missing iat", c -> c.remove("iat"));
        cases.put("stale by 61 s", c -> { c.put("iat", now - 61); c.put("exp", now + 600); });
        cases.put("future by 6 s", c -> { c.put("iat", now + 6); c.put("exp", now + 600); });
        cases.put("expired", c -> { c.put("iat", now - 30); c.put("exp", now - 1); });
        for (var entry : cases.entrySet()) {
            workload = actorWith(entry.getValue());
            int before = fx.as.requestCount();
            ExchangeException e = assertThrows(ExchangeException.class,
                    () -> exchanger.exchange(request(Set.of("cases:read"))), entry.getKey());
            assertEquals(Code.INPUT_REJECTED, e.code(), entry.getKey());
            assertEquals(before, fx.as.requestCount(), entry.getKey() + ": nothing was sent");
        }
    }

    @Test
    void theEdgesOfTheFreshnessWindowAreAcceptedAndOneSecondBeyondIsNot() throws Exception {
        long now = fx.clock.instant().getEpochSecond();
        workload = actorWith(c -> { c.put("iat", now - 60); c.put("exp", now + 600); });
        assertNotNull(exchanger.exchange(request(Set.of("cases:read"))));
        workload = actorWith(c -> { c.put("iat", now + 5); c.put("exp", now + 600); });
        assertNotNull(exchanger.exchange(request(Set.of("cases:read"))));
    }

    @Test
    void anActorThatTurnsStaleWhileTheIssuerIsAnsweringOrTheStoreIsSlowIsNeverReleased() {
        IssuedTokenBindings slow = new IssuedTokenBindings() {
            @Override
            public void bind(String issuer, String tokenId, Binding binding) {
                fx.bindings.bind(issuer, tokenId, binding);
                fx.clock.advance(Duration.ofSeconds(61)); // the workload proof is now older than 60 s
            }

            @Override
            public java.util.Optional<Binding> find(String issuer, String tokenId) {
                return fx.bindings.find(issuer, tokenId);
            }
        };
        Rfc8693TokenExchanger slowExchanger = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID,
                CLIENT_SECRET_NAME, fx.secrets, fx.grants, slow, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2));

        ExchangeException e = assertThrows(ExchangeException.class,
                () -> slowExchanger.exchange(request(Set.of("cases:read"))));

        assertEquals(Code.INPUT_REJECTED, e.code());
        assertTrue(e.getMessage().contains("stale"), e.getMessage());
    }

    @Test
    void aTokenThatExpiresOrLosesItsGrantWhileBeingBoundIsNeverReleased() {
        for (boolean revoke : new boolean[] {false, true}) {
            IssuedTokenBindings slow = new IssuedTokenBindings() {
                @Override
                public void bind(String issuer, String tokenId, Binding binding) {
                    fx.bindings.bind(issuer, tokenId, binding);
                    if (revoke) {
                        fx.grants.revoke(grant.id(), ALICE, "revoked during binding", null);
                    } else {
                        fx.clock.advance(Duration.ofSeconds(301)); // a slow store: the token is now past exp
                    }
                }

                @Override
                public java.util.Optional<Binding> find(String issuer, String tokenId) {
                    return fx.bindings.find(issuer, tokenId);
                }
            };
            Rfc8693TokenExchanger slowExchanger = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID,
                    CLIENT_SECRET_NAME, fx.secrets, fx.grants, slow, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2));

            ExchangeException e = assertThrows(ExchangeException.class,
                    () -> slowExchanger.exchange(request(Set.of("cases:read"))));

            assertNotNull(e.getMessage());
            assertFalse(e.getMessage().contains("eyJ"), "no token text in the refusal");
            if (!revoke) {
                assertEquals(Code.BAD_RESPONSE, e.code());
            }
        }
    }

    // ---- secrets ---------------------------------------------------------------------------------------------------

    @Test
    void aMissingClientSecretIsUnavailableAndNothingIsSent() {
        fx.secrets.remove(CLIENT_SECRET_NAME);

        ExchangeException e = refused(request(Set.of("cases:read")));

        assertEquals(Code.UNAVAILABLE, e.code());
        assertEquals(0, fx.as.requestCount());
        assertFalse(e.getMessage().contains(fx.clientSecret));
    }

    @Test
    void aRotatedClientSecretIsUsedOnTheNextExchangeAndTheOldOneStopsWorking() {
        exchanger.exchange(request(Set.of("cases:read")));

        fx.as.rotateClientSecret("rotated-" + fx.clientSecret);
        ExchangeException stale = refused(request(Set.of("cases:read")));
        assertEquals(Code.REFUSED, stale.code());
        assertEquals("invalid_client", stale.oauthError());

        fx.secrets.put(CLIENT_SECRET_NAME, "rotated-" + fx.clientSecret);
        assertNotNull(exchanger.exchange(request(Set.of("cases:read"))));
    }

    // ---- audit ---------------------------------------------------------------------------------------------------

    @Test
    void anIssueIsAuditedWithoutTheTokenOrAnySecret() {
        IssuedToken token = exchanger.exchange(request(Set.of("cases:read")));

        AuditEvent event = fx.sink.byAction(Rfc8693TokenExchanger.ISSUED).get(0);
        assertEquals(grant.id().value(), event.resourceId());
        assertEquals(AUDIENCE, event.attributes().get("audience"));
        assertEquals(token.tokenId(), event.attributes().get("tokenId"));
        String all = fx.sink.everything();
        assertFalse(all.contains(token.accessToken().reveal()));
        assertFalse(all.contains(userToken));
        assertFalse(all.contains(workload));
        assertFalse(all.contains(fx.clientSecret));
    }

    @Test
    void ifTheIssueCannotBeAuditedTheTokenIsDiscarded() {
        fx.sink.failing = true;

        ExchangeException e = refused(request(Set.of("cases:read")));

        assertEquals(Code.UNAVAILABLE, e.code());
    }

    // ---- misuse -------------------------------------------------------------------------------------------------------

    @Test
    void theEndpointMustBeHttpsUnlessItIsLoopback() {
        assertThrows(IllegalArgumentException.class, () -> new Rfc8693TokenExchanger("http://sso.example/token",
                CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets, fx.grants, fx.bindings, fx.verifier, fx.audit(), fx.clock,
                Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new Rfc8693TokenExchanger("https://u:p@sso.example/token",
                CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets, fx.grants, fx.bindings, fx.verifier, fx.audit(), fx.clock,
                Duration.ofSeconds(1)));
        assertNotNull(new Rfc8693TokenExchanger("https://sso.example/token", CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets,
                fx.grants, fx.bindings, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(1)));
    }

    @Test
    void aRequestNeverPrintsItsTokens() {
        String printed = request(Set.of("cases:read")).toString();

        assertFalse(printed.contains(userToken));
        assertFalse(printed.contains(workload));
    }
}
