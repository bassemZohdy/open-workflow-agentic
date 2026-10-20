package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.identity.AgentFakeIdpResource;
import io.github.bassemzohdy.openworkflow.agentic.identity.FakeIdpResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * WP-103: the governed admission path of {@code POST /agents/research} for a delegated token (a workflow step acting
 * for a user). The application verifies real RS256 tokens against the testkit's fake issuer; grants and bindings are the
 * real stores. Verified against the fake issuer only (docs/identity.md section 8). Every refusal is proved to open no
 * checkpoint thread.
 */
@QuarkusTest
@QuarkusTestResource(value = AgentFakeIdpResource.class, restrictToAnnotatedClass = true)
class AgentResourceDelegatedTest {

    private static final String RUN = "oa.agent.run";
    private static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "research-workflow");

    @Inject
    DelegationGrants grants;

    @Inject
    IssuedTokenBindings bindings;

    @Inject
    DataSource dataSource;

    private record Issued(String token, String jti, Instant expires) { }

    private DelegationGrant grantOf(String tenant, String user) {
        Subject subject = new Subject(SubjectKind.USER, user);
        return grants.grant(tenant, subject, subject, WORKFLOW, Set.of(RUN), Instant.now().plus(Duration.ofHours(1)), null);
    }

    /** A delegated token for the agent-run audience; {@code bind} records the binding of the exchanger. */
    private Issued issue(String tenant, String user, DelegationGrant grant, boolean bind, Object audience, String scope,
                         Duration lifetime, Consumer<Map<String, Object>> tweak) throws Exception {
        FakeIdentityProvider idp = FakeIdpResource.idp();
        Map<String, Object> claims = idp.baseClaims(user, null, lifetime);
        claims.put("aud", audience);
        claims.put("scope", scope);
        claims.put("tenant", tenant);
        claims.put("act", Map.of("sub", WORKFLOW.id()));
        tweak.accept(claims);
        String jti = (String) claims.get("jti");
        Instant exp = Instant.ofEpochSecond(((Number) claims.get("exp")).longValue());
        if (bind) {
            bindings.bind(idp.issuer(), jti, new IssuedTokenBindings.Binding(grant.id(), tenant, exp));
        }
        return new Issued(idp.signClaims(claims), jti, exp);
    }

    private Issued valid(String tenant, String user) throws Exception {
        return issue(tenant, user, grantOf(tenant, user), true, AgentFakeIdpResource.AGENT_AUDIENCE, RUN,
                Duration.ofMinutes(5), c -> { });
    }

    private static ValidatableResponse run(String token, String thread) {
        return given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body("{\"topic\":\"agentic workflows\",\"threadId\":\"" + thread + "\"}")
                .when().post("/agents/research").then();
    }

    private static String newTenant() {
        return "agent-" + UUID.randomUUID();
    }

    private static String newThread() {
        return "wp103-" + UlidGenerator.shared().next().value();
    }

    @Test
    void aBoundDelegatedTokenRunsTheAgentForTheUserOnTheUsersThreadAndWorksOnce() throws Exception {
        String tenant = newTenant();
        String thread = newThread();
        Issued issued = valid(tenant, "alice");
        run(issued.token(), thread).statusCode(200).body("threadId", equalTo(thread))
                .body("plan", containsString("[stub]"));
        String name = ThreadId.derive(tenant, "USER:alice", "direct:" + thread).value();
        assertEquals(1, scalar("select count(*) from lg4jthread where thread_name = '" + name + "'"));
        // single use: the same token again is refused and opens nothing new
        long threads = threads();
        run(issued.token(), thread).statusCode(401);
        assertEquals(threads, threads());
    }

    @Test
    void theDelegatedPrincipalIsTheUserNotTheActor() throws Exception {
        String tenant = newTenant();
        String thread = newThread();
        run(valid(tenant, "alice").token(), thread).statusCode(200);
        run(valid(tenant, "bob").token(), thread).statusCode(200);
        for (String user : new String[] {"alice", "bob"}) {
            String name = ThreadId.derive(tenant, "USER:" + user, "direct:" + thread).value();
            assertEquals(1, scalar("select count(*) from lg4jthread where thread_name = '" + name + "'"), user);
        }
        String actor = ThreadId.derive(tenant, "WORKFLOW:" + WORKFLOW.id(), "direct:" + thread).value();
        assertEquals(0, scalar("select count(*) from lg4jthread where thread_name = '" + actor + "'"));
    }

    @Test
    void everyRefusedDelegatedTokenIs401Or403AndOpensNoThread() throws Exception {
        String tenant = newTenant();
        String thread = newThread();
        long threads = threads();

        DelegationGrant grant = grantOf(tenant, "alice");
        // no binding: the token was not issued by the exchanger
        run(issue(tenant, "alice", grant, false, AgentFakeIdpResource.AGENT_AUDIENCE, RUN, Duration.ofMinutes(5), c -> { })
                .token(), thread).statusCode(401);
        // revoked grant
        DelegationGrant revoked = grantOf(tenant, "alice");
        Issued late = issue(tenant, "alice", revoked, true, AgentFakeIdpResource.AGENT_AUDIENCE, RUN,
                Duration.ofMinutes(5), c -> { });
        grants.revoke(revoked.id(), new Subject(SubjectKind.USER, "alice"), "test", null);
        run(late.token(), thread).statusCode(401);
        // the user audience (a token for the general API) or two audiences
        run(issue(tenant, "alice", grant, true, FakeIdpResource.AUDIENCE, RUN, Duration.ofMinutes(5), c -> { }).token(),
                thread).statusCode(401);
        run(issue(tenant, "alice", grant, true, java.util.List.of(AgentFakeIdpResource.AGENT_AUDIENCE,
                FakeIdpResource.AUDIENCE), RUN, Duration.ofMinutes(5), c -> { }).token(), thread).statusCode(401);
        // longer than the 300 second bound
        run(issue(tenant, "alice", grant, true, AgentFakeIdpResource.AGENT_AUDIENCE, RUN, Duration.ofMinutes(10),
                c -> { }).token(), thread).statusCode(401);
        // another tenant than the grant's
        Issued foreign = issue("other-" + tenant, "alice", grant, false, AgentFakeIdpResource.AGENT_AUDIENCE, RUN,
                Duration.ofMinutes(5), c -> { });
        bindings.bind(FakeIdpResource.idp().issuer(), foreign.jti(),
                new IssuedTokenBindings.Binding(grant.id(), tenant, foreign.expires()));
        run(foreign.token(), thread).statusCode(401);
        // a scope the grant does not hold is not granted, and a token without the resource scope is 403
        run(issue(tenant, "alice", grant, true, AgentFakeIdpResource.AGENT_AUDIENCE, "oa.inspect", Duration.ofMinutes(5),
                c -> { }).token(), thread).statusCode(403);
        run(issue(tenant, "alice", grant, true, AgentFakeIdpResource.AGENT_AUDIENCE, RUN + " oa.operate",
                Duration.ofMinutes(5), c -> { }).token(), thread).statusCode(401);
        // an actor chain that is not the grant's
        run(issue(tenant, "alice", grant, true, AgentFakeIdpResource.AGENT_AUDIENCE, RUN, Duration.ofMinutes(5),
                c -> c.put("act", Map.of("sub", "someone-else"))).token(), thread).statusCode(401);
        // garbage and no token
        run("not.a.jwt", thread).statusCode(401);
        given().contentType("application/json").body("{}").when().post("/agents/research").then().statusCode(401);

        assertEquals(threads, threads(), "no refusal opened a checkpoint thread");
    }

    @Test
    void aUsersOwnTokenStillWorksAndNeedsTheScope() throws Exception {
        FakeIdentityProvider idp = FakeIdpResource.idp();
        String tenant = newTenant();
        String thread = newThread();
        Map<String, Object> claims = idp.baseClaims("alice", FakeIdpResource.AUDIENCE, Duration.ofMinutes(5));
        claims.put("tenant", tenant);
        claims.put("scope", RUN);
        run(idp.signClaims(claims), thread).statusCode(200);
        claims = idp.baseClaims("alice", FakeIdpResource.AUDIENCE, Duration.ofMinutes(5));
        claims.put("tenant", tenant);
        claims.put("scope", "oa.inspect");
        run(idp.signClaims(claims), thread).statusCode(403);
    }

    @Test
    void aBadBodyIs400OnlyAfterAdmission() throws Exception {
        String tenant = newTenant();
        given().header("Authorization", "Bearer " + valid(tenant, "alice").token()).contentType("application/json")
                .body("{").when().post("/agents/research").then().statusCode(400);
        given().contentType("application/json").body("{").when().post("/agents/research").then().statusCode(401);
    }

    /** All checkpoint threads; the table exists only after the agent first runs. */
    private long threads() throws SQLException {
        if (scalar("select count(*) from information_schema.tables where table_name = 'lg4jthread'") == 0) {
            return 0;
        }
        return scalar("select count(*) from lg4jthread");
    }

    private long scalar(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }
}
