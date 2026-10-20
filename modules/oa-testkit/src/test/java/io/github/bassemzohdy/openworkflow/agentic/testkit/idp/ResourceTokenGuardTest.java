package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.ALICE;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_ID;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_SECRET_NAME;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.PLANNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InMemoryReplayCache;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JdbcReplayCache;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ReplayCache;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ResourceTokenGuard;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ResourceTokenGuard}: what a resource refuses. Tokens come from the real exchanger against the in-process
 * server, or are forged with the issuer's key to produce the cases an honest server never issues.
 */
class ResourceTokenGuardTest {

    private static final String RESOURCE = "tool-cases";
    private static final Set<String> SCOPES = Set.of("cases:read", "cases:write");

    private IdentityFixture fx;
    private DelegationGrant grant;
    private Rfc8693TokenExchanger exchanger;
    private ResourceTokenGuard guard;
    private String userToken;
    private String workload;

    @BeforeEach
    void start() throws Exception {
        fx = new IdentityFixture(RESOURCE, "tool-search");
        exchanger = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets,
                fx.grants, fx.bindings, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2));
        grant = fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, SCOPES, fx.clock.instant().plus(Duration.ofHours(1)),
                null);
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofMinutes(30));
        workload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
        guard = new ResourceTokenGuard(fx.verifier, RESOURCE, new InMemoryReplayCache(100), fx.grants, fx.bindings, fx.clock, 1);
    }

    @AfterEach
    void stop() {
        fx.close();
    }

    private IssuedToken issue(String audience, Set<String> scopes) {
        return exchanger.exchange(new ExchangeRequest(Credential.of(userToken), Credential.of(workload), audience,
                scopes, grant));
    }

    private Map<String, Object> forged(String audience) {
        Map<String, Object> claims = fx.idp.baseClaims("alice", audience, Duration.ofSeconds(120));
        claims.put("scope", "cases:read");
        claims.put("tenant", fx.tenant);
        claims.put("act", Map.of("sub", "planner"));
        return claims;
    }

    private Reason refusedWith(String token, Set<String> scopes) {
        return assertThrows(TokenRejectedException.class, () -> guard.admit(token, scopes)).reason();
    }

    // ---- accepted -------------------------------------------------------------------------------------------------

    @Test
    void aTokenIssuedForThisResourceIsAdmittedWithItsSubjectActorAndScopes() {
        IssuedToken token = issue(RESOURCE, Set.of("cases:read"));

        VerifiedToken admitted = guard.admit(token.accessToken().reveal(), Set.of("cases:read"));

        assertEquals("alice", admitted.subject().id());
        assertEquals("planner", admitted.actor());
        assertEquals(Set.of("cases:read"), admitted.scopes());
        assertEquals(fx.tenant, admitted.tenant());
    }

    // ---- audience binding ---------------------------------------------------------------------------------------

    @Test
    void aTokenForAnotherResourceIsRefusedHere() {
        IssuedToken forSearch = issue("tool-search", Set.of("cases:read"));

        assertEquals(Reason.AUDIENCE, refusedWith(forSearch.accessToken().reveal(), Set.of("cases:read")));
    }

    @Test
    void aTokenForSeveralAudiencesIsRefusedEvenIfOneIsThisResource() throws Exception {
        Map<String, Object> claims = forged(RESOURCE);
        claims.put("aud", List.of(RESOURCE, "tool-search"));

        assertEquals(Reason.AUDIENCE, refusedWith(fx.idp.signClaims(claims), Set.of("cases:read")));
    }

    @Test
    void theUsersOwnTokenIsRefusedSoPassthroughFailsAtTheResource() throws Exception {
        Map<String, Object> claims = fx.idp.baseClaims("alice", RESOURCE, Duration.ofSeconds(120));
        claims.put("scope", "cases:read");
        claims.put("tenant", fx.tenant);

        assertEquals(Reason.WRONG_TOKEN_KIND, refusedWith(fx.idp.signClaims(claims), Set.of("cases:read")));
        assertEquals(Reason.AUDIENCE, refusedWith(userToken, Set.of("cases:read")), "and it names the API, not the tool");
    }

    // ---- expiry -----------------------------------------------------------------------------------------------

    @Test
    void anExpiredTokenIsRefusedOnceTheSkewHasPassed() {
        IssuedToken token = issue(RESOURCE, Set.of("cases:read"));

        fx.clock.advance(Duration.ofSeconds(300 + 31));

        assertEquals(Reason.EXPIRED, refusedWith(token.accessToken().reveal(), Set.of("cases:read")));
    }

    @Test
    void aTokenThatWasIssuedForLongerThanFiveMinutesIsRefusedEvenWhileItIsValid() throws Exception {
        Map<String, Object> claims = fx.idp.baseClaims("alice", RESOURCE, Duration.ofSeconds(301));
        claims.put("scope", "cases:read");
        claims.put("tenant", fx.tenant);
        claims.put("act", Map.of("sub", "planner"));

        assertEquals(Reason.EXPIRED, refusedWith(fx.idp.signClaims(claims), Set.of("cases:read")));
    }

    // ---- replay -----------------------------------------------------------------------------------------------

    @Test
    void aTokenIsAcceptedOnceAndTheSecondUseIsAReplay() {
        String token = issue(RESOURCE, Set.of("cases:read")).accessToken().reveal();

        guard.admit(token, Set.of("cases:read"));

        assertEquals(Reason.REPLAYED, refusedWith(token, Set.of("cases:read")));
    }

    @Test
    void aTokenMayBeUsedAsOftenAsTheResourceAllowsAndNoMore() {
        ResourceTokenGuard three = new ResourceTokenGuard(fx.verifier, RESOURCE, new InMemoryReplayCache(100), fx.grants,
                fx.bindings, fx.clock, 3);
        String token = issue(RESOURCE, Set.of("cases:read")).accessToken().reveal();

        for (int i = 0; i < 3; i++) {
            three.admit(token, Set.of("cases:read"));
        }

        assertEquals(Reason.REPLAYED, assertThrows(TokenRejectedException.class,
                () -> three.admit(token, Set.of("cases:read"))).reason());
    }

    @Test
    void aRefusedTokenDoesNotUseUpItsUse() {
        String token = issue(RESOURCE, Set.of("cases:read")).accessToken().reveal();

        assertEquals(Reason.SCOPE, refusedWith(token, Set.of("cases:write")));

        guard.admit(token, Set.of("cases:read"));
    }

    @Test
    void aTokenWithoutAnIdCannotBeCheckedForReplayAndIsRefused() throws Exception {
        Map<String, Object> claims = forged(RESOURCE);
        claims.remove("jti");

        assertEquals(Reason.MISSING_CLAIM, refusedWith(fx.idp.signClaims(claims), Set.of("cases:read")));
    }

    @Test
    void aFailingReplayCacheRefusesInsteadOfAdmitting() {
        ReplayCache broken = (key, expiresAt, maxUses, now) -> {
            throw new IllegalStateException("database down");
        };
        ResourceTokenGuard failing = new ResourceTokenGuard(fx.verifier, RESOURCE, broken, fx.grants, fx.bindings, fx.clock, 1);
        String token = issue(RESOURCE, Set.of("cases:read")).accessToken().reveal();

        assertEquals(Reason.VERIFIER_UNAVAILABLE, assertThrows(TokenRejectedException.class,
                () -> failing.admit(token, Set.of("cases:read"))).reason());
    }

    // ---- scope and grant ---------------------------------------------------------------------------------------

    @Test
    void aScopeTheTokenLacksIsRefused() {
        String token = issue(RESOURCE, Set.of("cases:read")).accessToken().reveal();

        assertEquals(Reason.SCOPE, refusedWith(token, Set.of("cases:read", "cases:write")));
    }

    @Test
    void aRevokedGrantStopsATokenThatHasNotExpiredYet() {
        String token = issue(RESOURCE, Set.of("cases:read")).accessToken().reveal();

        fx.grants.revoke(grant.id(), ALICE, "withdrawn", null);

        assertEquals(Reason.GRANT_INACTIVE, refusedWith(token, Set.of("cases:read")),
                "revocation takes effect at the resource at once, not after the token expires");
    }

    @Test
    void aGuardWithoutGrantsOrBindingsCannotBeBuilt() {
        assertThrows(NullPointerException.class, () -> new ResourceTokenGuard(fx.verifier, RESOURCE,
                new InMemoryReplayCache(100), null, fx.bindings, fx.clock, 1));
        assertThrows(NullPointerException.class, () -> new ResourceTokenGuard(fx.verifier, RESOURCE,
                new InMemoryReplayCache(100), fx.grants, null, fx.clock, 1));
    }

    // ---- the replay caches themselves ------------------------------------------------------------------------

    @Test
    void theInMemoryCacheRefusesInsteadOfForgettingLiveTokensWhenFull() {
        InMemoryReplayCache cache = new InMemoryReplayCache(2);
        Instant now = fx.clock.instant();
        Instant later = now.plusSeconds(300);

        assertTrue(cache.use("a", later, 1, now));
        assertTrue(cache.use("b", later, 1, now));
        assertFalse(cache.use("c", later, 1, now), "full of live entries: refuse");
        assertFalse(cache.use("a", later, 1, now));
        assertTrue(cache.use("c", later, 1, now.plusSeconds(300 + 61)), "expired entries are dropped to make room");
    }

    @Test
    void theJdbcCacheLetsExactlyOneOfManyConcurrentUsesWinAndSurvivesARestart() throws Exception {
        String key = "iss|" + UUID.randomUUID();
        Instant now = fx.clock.instant();
        Instant expiry = now.plusSeconds(300);
        JdbcReplayCache cache = new JdbcReplayCache(IdentityFixture.dataSource());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            AtomicBoolean go = new AtomicBoolean();
            for (int i = 0; i < 16; i++) {
                Callable<Boolean> use = () -> {
                    while (!go.get()) {
                        Thread.onSpinWait();
                    }
                    return cache.use(key, expiry, 1, now);
                };
                results.add(pool.submit(use));
            }
            go.set(true);
            int winners = 0;
            for (Future<Boolean> f : results) {
                winners += f.get() ? 1 : 0;
            }
            assertEquals(1, winners, "single use means exactly one winner");
        } finally {
            pool.shutdownNow();
        }

        JdbcReplayCache afterRestart = new JdbcReplayCache(IdentityFixture.dataSource());
        assertFalse(afterRestart.use(key, expiry, 1, now), "a new process remembers the token");
    }

    @Test
    void theJdbcCacheCountsUpToTheLimitAndStoresOnlyTheKeyAndACount() throws Exception {
        String key = "iss|" + UUID.randomUUID();
        Instant now = fx.clock.instant();
        JdbcReplayCache cache = new JdbcReplayCache(IdentityFixture.dataSource());

        assertTrue(cache.use(key, now.plusSeconds(300), 2, now));
        assertTrue(cache.use(key, now.plusSeconds(300), 2, now));
        assertFalse(cache.use(key, now.plusSeconds(300), 2, now));

        try (Connection c = IdentityFixture.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select uses, expires_at from identity.token_use where token_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "a refused use is not counted");
            }
        }
    }

    // ---- the exact issuing grant (ADR 0021 H1) --------------------------------------------------------------------

    @Test
    void revokingTheIssuingGrantStopsTheTokenEvenWhileAnEquivalentGrantStaysActive() {
        DelegationGrant equivalent = fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, SCOPES,
                fx.clock.instant().plus(Duration.ofHours(1)), null);
        String token = issue(RESOURCE, Set.of("cases:read")).accessToken().reveal();

        fx.grants.revoke(grant.id(), ALICE, "withdrawn", null);

        assertTrue(fx.grants.check(equivalent.id()).active(), "the equivalent grant is still active");
        assertEquals(Reason.GRANT_INACTIVE, refusedWith(token, Set.of("cases:read")));
    }

    @Test
    void theTokenIsBoundToItsGrantBeforeItIsReturned() {
        IssuedToken token = issue(RESOURCE, Set.of("cases:read"));

        IssuedTokenBindings.Binding binding = fx.bindings.find(fx.idp.issuer(), token.tokenId()).orElseThrow();
        assertEquals(grant.id(), binding.grantId());
        assertEquals(fx.tenant, binding.tenant());
        assertEquals(token.expiresAt(), binding.expiresAt());
    }

    @Test
    void aTokenWithoutABindingIsRefusedEvenWhenAnActiveGrantCoversIt() {
        IssuedToken token = issue(RESOURCE, Set.of("cases:read"));
        fx.bindings.forget(fx.idp.issuer(), token.tokenId());

        assertEquals(Reason.GRANT_INACTIVE, refusedWith(token.accessToken().reveal(), Set.of("cases:read")));
    }

    @Test
    void aTokenSignedByTheIssuerButNeverExchangedHasNoBindingAndIsRefused() throws Exception {
        assertEquals(Reason.GRANT_INACTIVE, refusedWith(fx.idp.signClaims(forged(RESOURCE)), Set.of("cases:read")));
    }

    @Test
    void anOutageOfTheBindingStoreRefusesAtTheResourceAndDiscardsTheTokenAtTheExchange() {
        IssuedToken token = issue(RESOURCE, Set.of("cases:read"));

        fx.bindings.fail(true);
        assertEquals(Reason.VERIFIER_UNAVAILABLE, refusedWith(token.accessToken().reveal(), Set.of("cases:read")));
        ExchangeException e = assertThrows(ExchangeException.class, () -> issue(RESOURCE, Set.of("cases:read")));
        assertEquals(ExchangeException.Code.UNAVAILABLE, e.code());
        assertTrue(e.getMessage().contains("binding"), e.getMessage());

        fx.bindings.fail(false);
        assertEquals("alice", guard.admit(token.accessToken().reveal(), Set.of("cases:read")).subject().id(),
                "nothing was used up while the store was down");
    }

    @Test
    void anOldProofWithTheSameJtiButAnotherSignedExpiryIsRefused() throws Exception {
        IssuedToken issued = issue(RESOURCE, Set.of("cases:read"));
        Map<String, Object> claims = forged(RESOURCE);
        claims.put("jti", issued.tokenId());
        // same issuer, same jti, same grant facts, but a different signed exp than the one that was bound
        assertNotEquals(issued.expiresAt(), Instant.ofEpochSecond(((Number) claims.get("exp")).longValue()));

        assertEquals(Reason.GRANT_INACTIVE, refusedWith(fx.idp.signClaims(claims), Set.of("cases:read")));
    }

    @Test
    void bindingTheSameTokenAgainChangesNothingUnlessEverythingIsIdentical() {
        IssuedToken issued = issue(RESOURCE, Set.of("cases:read"));
        IssuedTokenBindings.Binding same = fx.bindings.find(fx.idp.issuer(), issued.tokenId()).orElseThrow();

        fx.bindings.bind(fx.idp.issuer(), issued.tokenId(), same); // an idempotent retry is accepted

        for (IssuedTokenBindings.Binding changed : List.of(
                new IssuedTokenBindings.Binding(same.grantId(), same.tenant(), same.expiresAt().plusSeconds(1)),
                new IssuedTokenBindings.Binding(same.grantId(), "another-tenant", same.expiresAt()),
                new IssuedTokenBindings.Binding(fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, SCOPES,
                        fx.clock.instant().plus(Duration.ofHours(1)), null).id(), same.tenant(), same.expiresAt()))) {
            assertThrows(IllegalStateException.class, () -> fx.bindings.bind(fx.idp.issuer(), issued.tokenId(), changed));
        }
        assertEquals(same, fx.bindings.find(fx.idp.issuer(), issued.tokenId()).orElseThrow(), "nothing was updated");
    }
}
