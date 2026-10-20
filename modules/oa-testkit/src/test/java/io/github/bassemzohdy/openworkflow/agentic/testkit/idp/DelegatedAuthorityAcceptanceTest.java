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

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.PermissionWideningException;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GrantException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InMemoryReplayCache;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JdbcReplayCache;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ResourceTokenGuard;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.CedarPolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicySnapshot;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PublishedPolicySnapshot;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T07, "Reject agent-to-agent scope widening and expired/replayed delegated tokens" (M11, M12), with two scripted
 * agents: the planner holds a grant from Alice and passes part of it to the worker.
 *
 * <p>What this proves, and against what:
 * <ul>
 *   <li>Widening is refused when the worker or the planner asks for more scope, a longer life, or a grant that is not
 *       theirs: in the grant service, in the database (the service bypassed), in the token exchange, at the resource
 *       (a forged token with a wider scope), and in the agent task contract.</li>
 *   <li>An expired token is refused by the resource; an exchange against an expired or revoked grant is refused; a
 *       revoked chain stops tokens that are still within their lifetime.</li>
 *   <li>A token is accepted once; a second use is refused, also by a second guard and after a restart (the
 *       database-backed cache).</li>
 *   <li>M12: the policy enforcement point, with the real Cedar engine, denies a delegated tool call that is outside the
 *       delegated scope, names the policy and its version in an audit record, and refuses when the engine or the audit
 *       cannot be reached.</li>
 * </ul>
 * Against: the testkit's in-process authorization server and issuer (a fake, not Keycloak), PostgreSQL, and the embedded
 * Cedar engine. Nothing here runs against a real identity provider or a real multi-agent flow (WP-207).
 */
@Acceptance(value = "T07", requirements = {"M11", "M12"})
class DelegatedAuthorityAcceptanceTest {

    private static final String RESOURCE = "tool-cases";
    private static final Set<String> PLANNER_SCOPES = Set.of("cases:read", "cases:write");

    private IdentityFixture fx;
    private Rfc8693TokenExchanger exchanger;
    private ResourceTokenGuard guard;
    private DelegationGrant plannerGrant;
    private DelegationGrant workerGrant;
    private String userToken;
    private String plannerWorkload;
    private String workerWorkload;

    @BeforeEach
    void twoAgents() throws Exception {
        fx = new IdentityFixture(RESOURCE, CLIENT_ID);
        exchanger = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets,
                fx.grants, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2));
        guard = new ResourceTokenGuard(fx.verifier, RESOURCE, new InMemoryReplayCache(100), fx.grants, fx.clock, 1);
        Instant in1h = fx.clock.instant().plus(Duration.ofHours(1));
        plannerGrant = fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, PLANNER_SCOPES, in1h, null);
        workerGrant = fx.grants.delegate(plannerGrant.id(), PLANNER, WORKER, Set.of("cases:read"),
                fx.clock.instant().plus(Duration.ofMinutes(30)), null);
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", PLANNER_SCOPES, Duration.ofMinutes(30));
        plannerWorkload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
        workerWorkload = fx.as.workloadToken("worker", fx.tenant, Duration.ofMinutes(30));
    }

    @AfterEach
    void stop() {
        fx.close();
    }

    /**
     * A root grant exchanges the user's token. A child grant exchanges its parent's delegated token (addressed to the
     * runtime), so that the whole chain stays in the token: the exchanger refuses a chain it cannot see in full.
     */
    private IssuedToken exchange(DelegationGrant grant, String workload, Set<String> scopes) {
        Credential subject = Credential.of(userToken);
        if (grant.parentId() != null) {
            try {
                String parentWorkload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
                subject = exchanger.exchange(new ExchangeRequest(subject, Credential.of(parentWorkload), CLIENT_ID,
                        PLANNER_SCOPES, plannerGrant)).accessToken();
            } catch (Exception e) {
                throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
            }
        }
        return exchanger.exchange(new ExchangeRequest(subject, Credential.of(workload), RESOURCE, scopes, grant));
    }

    private Reason refusedAtResource(String token, Set<String> scopes) {
        return assertThrows(TokenRejectedException.class, () -> guard.admit(token, scopes)).reason();
    }

    // ---- scope widening ---------------------------------------------------------------------------------------------

    @Test
    void anAgentCannotPassOnMoreThanItHoldsInScopeLifeOrIdentity() {
        Subject third = new Subject(SubjectKind.AGENT, "third");
        Instant sooner = fx.clock.instant().plus(Duration.ofMinutes(10));

        // The worker holds cases:read. It cannot hand out cases:write, nor an unrelated scope.
        assertThrows(PermissionWideningException.class, () -> fx.grants.delegate(workerGrant.id(), WORKER, third,
                Set.of("cases:write"), sooner, null));
        assertThrows(PermissionWideningException.class, () -> fx.grants.delegate(workerGrant.id(), WORKER, third,
                Set.of("cases:read", "admin"), sooner, null));
        // It cannot hand out a grant that outlives its own.
        assertThrows(PermissionWideningException.class, () -> fx.grants.delegate(workerGrant.id(), WORKER, third,
                Set.of("cases:read"), workerGrant.expiresAt().plusSeconds(1), null));
        // The planner cannot use the worker's grant, and the worker cannot use the planner's.
        assertEquals(GrantException.Code.FORBIDDEN, assertThrows(GrantException.class, () -> fx.grants.delegate(
                workerGrant.id(), PLANNER, third, Set.of("cases:read"), sooner, null)).code());
        assertEquals(GrantException.Code.FORBIDDEN, assertThrows(GrantException.class, () -> fx.grants.delegate(
                plannerGrant.id(), WORKER, third, Set.of("cases:read"), sooner, null)).code());
        // An agent already in the chain cannot be added again to loop authority around.
        assertEquals(GrantException.Code.CYCLE, assertThrows(GrantException.class, () -> fx.grants.delegate(
                workerGrant.id(), WORKER, PLANNER, Set.of("cases:read"), sooner, null)).code());
        // Nobody but the user gives a root grant.
        assertEquals(GrantException.Code.FORBIDDEN, assertThrows(GrantException.class, () -> fx.grants.grant(fx.tenant,
                ALICE, WORKER, WORKER, Set.of("cases:read"), sooner, null)).code());
    }

    @Test
    void theDatabaseRefusesAWiderOrLongerChildEvenWhenTheServiceIsBypassed() throws Exception {
        String insert = "insert into identity.delegation_grant (id, tenant, subject_kind, subject_id, agent_kind,"
                + " agent_id, scopes, parent_grant_id, depth, chain, status, created_at, created_by_kind,"
                + " created_by_id, expires_at, version) values (?, ?, 'USER', 'alice', 'AGENT', 'sneaky', ?::text[],"
                + " ?, 2, ?::text[], 'ACTIVE', ?, 'AGENT', 'worker', ?, 0)";
        String chain = "{planner,worker,sneaky}";
        Timestamp now = Timestamp.from(fx.clock.instant());

        SQLException wider = tryInsert(insert, "{cases:write}", workerGrant, chain, now,
                Timestamp.from(workerGrant.expiresAt()));
        assertNotNull(wider, "a child wider than its parent must be refused by the database");
        assertTrue(wider.getMessage().contains("grant narrowing"), wider.getMessage());

        SQLException longer = tryInsert(insert, "{cases:read}", workerGrant, chain, now,
                Timestamp.from(workerGrant.expiresAt().plusSeconds(1)));
        assertNotNull(longer);
        assertTrue(longer.getMessage().contains("later than the parent"), longer.getMessage());

        assertEquals(null, tryInsert(insert, "{cases:read}", workerGrant, chain, now,
                Timestamp.from(workerGrant.expiresAt())), "the same insert, narrower, passes: only the rule differs");
    }

    private SQLException tryInsert(String sql, String scopes, DelegationGrant parent, String chain, Timestamp created,
                                   Timestamp expires) throws SQLException {
        try (Connection c = IdentityFixture.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, UlidGenerator.shared().next().value());
            ps.setString(2, fx.tenant);
            ps.setString(3, scopes);
            ps.setString(4, parent.id().value());
            ps.setString(5, chain);
            ps.setTimestamp(6, created);
            ps.setTimestamp(7, expires);
            ps.executeUpdate();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    @Test
    void theExchangeNeverIssuesMoreThanTheGrantAndTheResourceRefusesAForgedWiderToken() throws Exception {
        // The worker's grant covers cases:read. Asking for cases:write is refused before anything is sent.
        ExchangeException widening = assertThrows(ExchangeException.class, () -> exchanger.exchange(new ExchangeRequest(
                Credential.of(userToken), Credential.of(workerWorkload), RESOURCE, Set.of("cases:write"), workerGrant)));
        assertEquals(ExchangeException.Code.SCOPE_WIDENING, widening.code());
        assertEquals(0, fx.as.received().size(), "the request never reached the authorization server");

        // The worker cannot use the planner's grant (a grant names its agent).
        assertThrows(ExchangeException.class, () -> exchange(plannerGrant, workerWorkload, Set.of("cases:read")));

        // A token that an honest server would never issue: the worker's identity, scope cases:write. The resource
        // checks the grant behind the token, so the signature being valid is not enough.
        Map<String, Object> claims = fx.idp.baseClaims("alice", RESOURCE, Duration.ofSeconds(120));
        claims.put("scope", "cases:write");
        claims.put("tenant", fx.tenant);
        claims.put("act", Map.of("sub", "worker"));
        assertEquals(Reason.GRANT_INACTIVE, refusedAtResource(fx.idp.signClaims(claims), Set.of("cases:write")),
                "the worker's grant does not cover cases:write");

        // The same worker, with what it is entitled to, is admitted: the refusal is about the scope, nothing else.
        IssuedToken allowed = exchange(workerGrant, workerWorkload, Set.of("cases:read"));
        assertEquals("worker", guard.admit(allowed.accessToken().reveal(), Set.of("cases:read")).actor());
    }

    @Test
    void theAgentTaskContractCannotBeWidenedByAChildTask() {
        AgentTaskContract plannerTask = task(plannerGrant.id(), Set.of("cases:read", "cases:write"));
        Ulid childId = UlidGenerator.shared().next();
        ExecutionLimits limits = new ExecutionLimits(5, Duration.ofMinutes(1), 1_000L);

        assertThrows(PermissionWideningException.class, () -> plannerTask.narrow(childId, Set.of("search"),
                Set.of("cases:read", "admin"), RiskTier.LOW, limits));
        AgentTaskContract narrowed = plannerTask.narrow(childId, Set.of("search"), Set.of("cases:read"), RiskTier.LOW,
                limits);
        assertEquals(Set.of("cases:read"), narrowed.permittedDataScopes());
    }

    // ---- expired tokens ---------------------------------------------------------------------------------------------

    @Test
    void anExpiredTokenIsRefusedAndNoNewOneIsIssuedUnderAnExpiredOrRevokedGrant() throws Exception {
        IssuedToken token = exchange(workerGrant, workerWorkload, Set.of("cases:read"));
        assertTrue(token.expiresAt().isBefore(fx.clock.instant().plusSeconds(301)), "a token lives at most five minutes");

        fx.clock.advance(Duration.ofSeconds(300 + 31));
        assertEquals(Reason.EXPIRED, refusedAtResource(token.accessToken().reveal(), Set.of("cases:read")));

        // The grant ends 30 minutes in. After that, nothing is issued under it, even with valid subject and workload
        // tokens (their own lifetimes are 30 minutes, so they are refreshed).
        fx.clock.advance(Duration.ofMinutes(30));
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", PLANNER_SCOPES, Duration.ofMinutes(30));
        workerWorkload = fx.as.workloadToken("worker", fx.tenant, Duration.ofMinutes(30));
        assertEquals(ExchangeException.Code.GRANT_INACTIVE, assertThrows(ExchangeException.class,
                () -> exchange(workerGrant, workerWorkload, Set.of("cases:read"))).code());
    }

    @Test
    void revokingTheParentStopsTheChildAndTheTokensItAlreadyHolds() {
        IssuedToken live = exchange(workerGrant, workerWorkload, Set.of("cases:read"));

        fx.grants.revoke(plannerGrant.id(), ALICE, "alice withdrew the planner", null);

        assertEquals(Reason.GRANT_INACTIVE, refusedAtResource(live.accessToken().reveal(), Set.of("cases:read")),
                "a token that has not expired yet stops working when its grant chain ends");
        assertEquals(ExchangeException.Code.GRANT_INACTIVE, assertThrows(ExchangeException.class,
                () -> exchange(workerGrant, workerWorkload, Set.of("cases:read"))).code());
    }

    // ---- replayed tokens --------------------------------------------------------------------------------------------

    @Test
    void aTokenIsAcceptedOnceAndEveryLaterUseIsAReplayEvenAfterARestart() {
        JdbcReplayCache shared = new JdbcReplayCache(IdentityFixture.dataSource());
        ResourceTokenGuard first = new ResourceTokenGuard(fx.verifier, RESOURCE, shared, fx.grants, fx.clock, 1);
        String token = exchange(workerGrant, workerWorkload, Set.of("cases:read")).accessToken().reveal();

        first.admit(token, Set.of("cases:read"));

        assertEquals(Reason.REPLAYED, assertThrows(TokenRejectedException.class,
                () -> first.admit(token, Set.of("cases:read"))).reason());
        ResourceTokenGuard afterRestart = new ResourceTokenGuard(fx.verifier, RESOURCE,
                new JdbcReplayCache(IdentityFixture.dataSource()), fx.grants, fx.clock, 1);
        assertEquals(Reason.REPLAYED, assertThrows(TokenRejectedException.class,
                () -> afterRestart.admit(token, Set.of("cases:read"))).reason(), "a new process remembers the token");
    }

    @Test
    void aTokenIsBoundToItsResourceAndTheUsersOwnTokenIsNotADelegatedOne() throws Exception {
        String workerToken = exchange(workerGrant, workerWorkload, Set.of("cases:read")).accessToken().reveal();

        // Another resource server does not accept it (audience binding).
        ResourceTokenGuard other = new ResourceTokenGuard(fx.verifier, "tool-search", new InMemoryReplayCache(10),
                fx.grants, fx.clock, 1);
        assertEquals(Reason.AUDIENCE, assertThrows(TokenRejectedException.class,
                () -> other.admit(workerToken, Set.of("cases:read"))).reason());
        // The user's own token is not a delegated token.
        assertEquals(Reason.AUDIENCE, refusedAtResource(userToken, Set.of("cases:read")));
    }

    // ---- M12: the policy boundary outside the agents -------------------------------------------------------------------

    private static final String POLICY = """
            @id("tools.permit-delegated-read")
            permit (principal is Agent, action == Action::"tool_call", resource)
            when {
                context.scopes.contains("cases:read") &&
                context.delegationGrantId != "" &&
                context.permittedByContract == true
            };
            """;

    private AgentTaskContract task(Ulid grantId, Set<String> scopes) {
        return new AgentTaskContract(UlidGenerator.shared().next(), "Look at the case", Set.of("search"), scopes,
                RiskTier.LOW, new ExecutionLimits(20, Duration.ofMinutes(10), 1_000_000L), ALICE, grantId);
    }

    @Test
    void thePolicyBoundaryDeniesADelegatedCallOutsideItsScopeRecordsWhyAndFailsClosedWhenItCannotDecide() {
        CedarPolicyDecisionPoint cedar = new CedarPolicyDecisionPoint(fx.clock);
        cedar.install(new PublishedPolicySnapshot("2026-10-19.1", PolicySnapshot.hashOf("2026-10-19.1", List.of(POLICY)),
                List.of(POLICY)));
        Pep pep = new Pep(cedar, fx.sink, Duration.ofSeconds(5), fx.clock);

        Decision allowed = pep.toolCall(WORKER, task(workerGrant.id(), Set.of("cases:read")), "search", 1_000L);
        Decision denied = pep.toolCall(WORKER, task(workerGrant.id(), Set.of("cases:write")), "search", 1_000L);

        assertEquals(DecisionOutcome.ALLOW, allowed.outcome());
        assertEquals(DecisionOutcome.DENY, denied.outcome(), "a call that carries a scope the policy does not permit");
        assertEquals("2026-10-19.1", denied.policy().policySetVersion(), "the decision names the policy version");
        assertThrows(PolicyDenied.class, () -> Pep.guard(denied, () -> "the tool ran"));
        assertTrue(fx.sink.events.stream().anyMatch(e -> e.actor().equals(WORKER)
                        && e.attributes().values().stream().anyMatch("2026-10-19.1"::equals)),
                "the denial is attributed to the worker and carries the version: " + fx.sink.everything());

        // The engine cannot decide: refused, not allowed.
        Pep brokenEngine = new Pep(request -> {
            throw new IllegalStateException("policy service down");
        }, fx.sink, Duration.ofSeconds(5), fx.clock);
        Decision unavailable = brokenEngine.toolCall(WORKER, task(workerGrant.id(), Set.of("cases:read")), "search", 1L);
        assertEquals(DecisionOutcome.ERROR, unavailable.outcome());
        assertFalse(unavailable.allows());

        // The decision cannot be recorded: refused as well, so nothing is allowed without a record.
        fx.sink.failing = true;
        Decision unrecorded = pep.toolCall(WORKER, task(workerGrant.id(), Set.of("cases:read")), "search", 1_000L);
        assertEquals(DecisionOutcome.ERROR, unrecorded.outcome());
    }
}
