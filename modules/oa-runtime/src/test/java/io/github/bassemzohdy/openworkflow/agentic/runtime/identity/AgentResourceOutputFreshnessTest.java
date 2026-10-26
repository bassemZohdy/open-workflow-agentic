package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.AgentResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * WP-103: the private plan, draft and review built by a blocking agent run are withheld when the authority of the caller
 * ends DURING that run. The resource, resolver, admission, token guard, delegation grants and PEP are the real classes
 * (grants on a real PostgreSQL in a private, throwaway, migrated database); the verifier, policy rule, audit sink, agent and
 * the availability switch of the grant store are doubles. "During the run" is modelled by the agent double moving the test
 * clock or revoking the grant before it returns; this is deterministic and artificial, not a real slow model.
 *
 * <p>What this does NOT prove: any per-node or provider fence, that no effect happened, or that the work was undone.
 * A post-run refusal follows completed agent work (the run count is 1); only the output is withheld. The early refusals
 * before the run still leave zero invocations, and the tests keep the two apart.
 */
class AgentResourceOutputFreshnessTest {

    private static final String AUDIENCE = "oa-agent-run";
    private static final String ISSUER = "https://idp.test";
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "research-workflow");
    private static final String PLAN = "PRIVATE-PLAN-8f3a";
    private static final String DRAFT = "PRIVATE-DRAFT-8f3a";
    private static final String REVIEW = "PRIVATE-REVIEW-8f3a";
    private static final String DATABASE = "oa_agent_out_" + UUID.randomUUID().toString().replace("-", "");
    private static final String NAME = "oa_agent_out_[0-9a-f]{32}";
    private static boolean created;

    /** The real database, with a switch that makes every connection fail (an unavailable grant store). */
    private static final class Switchable extends PGSimpleDataSource {
        final AtomicBoolean down = new AtomicBoolean();

        @Override
        public Connection getConnection() throws SQLException {
            if (down.get()) {
                throw new SQLException("grant store down");
            }
            return super.getConnection();
        }
    }

    private Switchable store;
    private MovableClock clock;
    private AtomicInteger runs;
    private Runnable duringAudit;
    private Runnable duringRun;
    private DelegationGrants grants;
    private DelegationGrant grant;
    private AgentResource delegated;
    private AgentResource user;

    @BeforeAll
    static void createPrivateDatabase() throws Exception {
        if (!DATABASE.matches(NAME)) {
            throw new IllegalStateException("unexpected private database name");
        }
        try (Connection c = source("postgres", new PGSimpleDataSource()).getConnection();
                Statement s = c.createStatement()) {
            s.execute("create database " + DATABASE);
            created = true;
        }
        PGSimpleDataSource real = source(DATABASE, new PGSimpleDataSource());
        try (Connection c = real.getConnection(); Statement s = c.createStatement();
                java.sql.ResultSet r = s.executeQuery("select current_database()")) {
            if (!r.next() || !DATABASE.equals(r.getString(1))) {
                throw new IllegalStateException("not connected to the private database; refusing to migrate");
            }
        }
        Flyway.configure().dataSource(real).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void dropPrivateDatabase() throws Exception {
        if (!created || !DATABASE.matches(NAME)) {
            return;
        }
        // no FORCE: a leaked connection fails loudly instead of being killed
        try (Connection c = source("postgres", new PGSimpleDataSource()).getConnection();
                Statement s = c.createStatement()) {
            s.execute("drop database " + DATABASE);
        }
    }

    private static <T extends PGSimpleDataSource> T source(String database, T ds) {
        ds.setUrl(IdentityTestSupport.URL);
        ds.setUser(IdentityTestSupport.USER);
        ds.setPassword(IdentityTestSupport.PASSWORD);
        ds.setDatabaseName(database);
        return ds;
    }

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
        runs = new AtomicInteger();
        duringAudit = () -> { };
        duringRun = () -> { };
        store = source(DATABASE, new Switchable());
        ResearchAgent agent = new ResearchAgent() {
            @Override
            public ResearchState run(String threadId, String topic) {
                runs.incrementAndGet();
                duringRun.run(); // the blocking work: authority may end while it runs
                return new ResearchState(Map.of(ResearchState.TOPIC, topic, ResearchState.PLAN, PLAN,
                        ResearchState.DRAFT, DRAFT, ResearchState.REVIEW, REVIEW));
            }
        };
        Pep pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "ok", new PolicyRef("test.permit", "1"),
                clock.instant()), (AuditSink) (AuditEvent event) -> duringAudit.run());

        // the own token of a user: real resolver, no database
        VerifiedToken own = new VerifiedToken(ISSUER, ALICE, Set.of("oa-api"), Set.of(AgentResourceAdmission.SCOPE),
                "tenant-a", clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), "jti-user", List.of());
        user = new AgentResource(agent, new CallerContextResolver((bearer, audience) -> own, "oa-api", false,
                Set.of("test"), Set.of("test"), clock), pep, new ObjectMapper(),
                new AgentResourceAdmission(null, null, null, null, clock));

        // a delegated token: real admission, guard and grants on the private database
        grants = new DelegationGrants(new DelegationGrantStore(store),
                IdentityTestSupport.audit(new IdentityTestSupport.RecordingSink(), clock), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        String tenant = IdentityTestSupport.newTenant();
        grant = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        Instant exp = clock.instant().plus(Duration.ofMinutes(5));
        VerifiedToken token = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of(AgentResourceAdmission.SCOPE),
                tenant, clock.instant(), exp, UUID.randomUUID().toString(), List.of(WORKFLOW.id()));
        var bindings = new InMemoryIssuedTokenBindings();
        bindings.bind(ISSUER, token.tokenId(), new IssuedTokenBindings.Binding(grant.id(), tenant, exp));
        ResourceTokenGuard guard = new ResourceTokenGuard((bearer, audience) -> token, AUDIENCE,
                new InMemoryReplayCache(100), grants, bindings, clock, 1);
        var calls = new ResourceCallFixtures.InMemory();
        calls.bind(ResourceCallFixtures.binding(token, grant.id(), ResourceCallFixtures.scope(tenant, ALICE, AUDIENCE,
                Pep.AGENT_RESEARCH_RUN, ResourceCallFixtures.TOPIC_REQUEST)));
        delegated = new AgentResource(agent, null, pep, new ObjectMapper(),
                new AgentResourceAdmission(guard, calls, bindings, AUDIENCE, clock));
    }

    private static HttpHeaders bearer() {
        return (HttpHeaders) java.lang.reflect.Proxy.newProxyInstance(HttpHeaders.class.getClassLoader(),
                new Class<?>[] {HttpHeaders.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getRequestHeader")) {
                        return List.of("Bearer a.b.c");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static WebApplicationException refused(AgentResource resource) {
        return assertThrows(WebApplicationException.class, () -> resource.run(bearer(), "{\"topic\":\"x\"}"));
    }

    /** The refusal carries the status only: no entity, and no private text anywhere in its messages. */
    private static void assertNothingPrivate(WebApplicationException refusal) {
        assertFalse(refusal.getResponse().hasEntity());
        for (Throwable t = refusal; t != null && t.getCause() != t; t = t.getCause()) {
            String text = String.valueOf(t.getMessage());
            for (String secret : List.of(PLAN, DRAFT, REVIEW)) {
                assertFalse(text.contains(secret), "private output leaked into " + t.getClass().getSimpleName());
            }
        }
    }

    private static void assertInvalidToken(WebApplicationException refusal) {
        assertEquals(401, refusal.getResponse().getStatus());
        assertEquals("Bearer error=\"invalid_token\"", refusal.getResponse().getHeaderString(HttpHeaders.WWW_AUTHENTICATE));
    }

    // ---- user token ----

    @Test
    void aValidUserCallerGetsThePrivateOutputAfterTheRun() {
        var out = user.run(bearer(), "{\"topic\":\"x\"}");
        assertEquals(PLAN, out.get("plan"));
        assertEquals(DRAFT, out.get("draft"));
        assertEquals(REVIEW, out.get("review"));
        assertEquals(1, runs.get());
    }

    @Test
    void aUserTokenThatExpiresDuringTheRunCompletedTheWorkButWithholdsTheOutput() {
        duringRun = () -> clock.advance(Duration.ofMinutes(6));
        var refusal = refused(user);
        assertInvalidToken(refusal);
        assertEquals(1, runs.get(), "the work completed; only the output is withheld");
        assertNothingPrivate(refusal);
    }

    @Test
    void aUserTokenThatExpiresBeforeTheRunStillRunsNothing() {
        duringAudit = () -> clock.advance(Duration.ofMinutes(6));
        assertInvalidToken(refused(user));
        assertEquals(0, runs.get(), "early refusal: the agent was never invoked");
    }

    // ---- delegated token ----

    @Test
    void aValidDelegatedCallerGetsThePrivateOutputAndTheSingleUseIsConsumedOnce() {
        var out = delegated.run(bearer(), "{\"topic\":\"x\"}");
        assertEquals(PLAN, out.get("plan"));
        assertEquals(DRAFT, out.get("draft"));
        assertEquals(REVIEW, out.get("review"));
        assertEquals(1, runs.get());
        assertEquals(401, refused(delegated).getResponse().getStatus(), "a replay is refused at admission");
        assertEquals(1, runs.get());
    }

    @Test
    void aDelegatedTokenThatExpiresDuringTheRunWithholdsTheOutputAndCannotBeReplayed() {
        duringRun = () -> clock.advance(Duration.ofMinutes(6));
        var refusal = refused(delegated);
        assertInvalidToken(refusal);
        assertEquals(1, runs.get());
        assertNothingPrivate(refusal);
        assertEquals(401, refused(delegated).getResponse().getStatus());
        assertEquals(1, runs.get(), "the credential is not usable again");
    }

    @Test
    void aGrantRevokedDuringTheRunWithholdsTheOutput() {
        duringRun = () -> grants.revoke(grant.id(), ALICE, "revoked during the run", null);
        var refusal = refused(delegated);
        assertInvalidToken(refusal);
        assertEquals(1, runs.get());
        assertNothingPrivate(refusal);
    }

    @Test
    void aGrantStoreThatCannotBeReadAfterTheRunIs503WithANeutralOutputWithheldMessage() {
        duringRun = () -> store.down.set(true);
        var refusal = refused(delegated);
        assertEquals(503, refusal.getResponse().getStatus());
        assertEquals("the authority could not be checked; the output was withheld", refusal.getMessage());
        assertFalse(refusal.getMessage().contains("was not run"), "the agent did run");
        assertEquals(1, runs.get());
        assertNothingPrivate(refusal);
    }

    @Test
    void aGrantRevokedBeforeTheRunStillRunsNothing() {
        duringAudit = () -> grants.revoke(grant.id(), ALICE, "revoked during audit", null);
        assertInvalidToken(refused(delegated));
        assertEquals(0, runs.get(), "early refusal: the agent was never invoked");
    }

    @Test
    void aGrantStoreThatCannotBeReadBeforeTheRunIs503AndRunsNothing() {
        duringAudit = () -> store.down.set(true);
        var refusal = refused(delegated);
        assertEquals(503, refusal.getResponse().getStatus());
        assertEquals("the authority could not be checked; the agent was not run", refusal.getMessage());
        assertEquals(0, runs.get());
        assertTrue(store.down.get());
    }
}
