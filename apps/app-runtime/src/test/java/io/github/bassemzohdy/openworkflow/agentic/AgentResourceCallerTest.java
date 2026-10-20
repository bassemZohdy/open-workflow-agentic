package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.AgentResource;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * WP-103: {@code POST /agents/research} needs a verified caller and a policy decision. Every refusal is proved to leave
 * no business effect: the agent writes checkpoint rows for its thread, so a refused call must leave none (the agent
 * did not run), and the policy decision is audited when the policy was asked.
 */
@QuarkusTest
@io.quarkus.test.junit.TestProfile(io.github.bassemzohdy.openworkflow.agentic.lifecycle.TrustedCallerProfile.class)
class AgentResourceCallerTest {

    private static final String RUN = "oa.agent.run";

    @Inject
    DataSource dataSource;

    private static RequestSpecification caller(String tenant, String scopes) {
        RequestSpecification spec = given().header("X-Tenant", tenant).header("X-Subject", "alice");
        return scopes == null ? spec : spec.header("X-Scopes", scopes);
    }

    private static String newThread() {
        return "wp103-" + UlidGenerator.shared().next().value();
    }

    @Test
    void agentGraphRunsAndCheckpoints() {
        given().header("X-Tenant", "t-agent").header("X-Subject", "alice").header("X-Scopes", "oa.agent.run")
                .contentType("application/json").body("{\"topic\":\"agentic workflows\"}")
                .when().post("/agents/research")
                .then().statusCode(200)
                .body("threadId", notNullValue())
                .body("plan", containsString("[stub]"))
                .body("review", containsString("[stub]"));
    }

    @Test
    void anAnonymousCallerIs401WhateverTheBodyAndTheAgentDoesNotRun() throws Exception {
        String thread = newThread();
        long allThreads = threads();
        for (String body : List.of("{\"topic\":\"x\",\"threadId\":\"" + thread + "\"}", "{", "", "[]")) {
            given().contentType("application/json").body(body).when().post("/agents/research")
                    .then().statusCode(401);
        }
        // a claimed identity without a token is not a caller either (the header seam is trusted in %test, so the claim
        // is only refused for want of X-Tenant/X-Subject: the anonymous case above sends neither)
        assertEquals(allThreads, threads(), "a refused call opens no checkpoint thread");
    }

    @Test
    void aCallerWithoutTheScopeIsRefusedAndTheAgentDoesNotRun() throws Exception {
        String tenant = "t-" + UlidGenerator.shared().next().value();
        String thread = newThread();
        long before = deniedDecisions();
        long allThreads = threads();
        for (String scopes : new String[] {null, "", "oa.inspect", "oa.operate,oa.recover", "oa.agent.runner"}) {
            caller(tenant, scopes).contentType("application/json")
                    .body("{\"topic\":\"x\",\"threadId\":\"" + thread + "\"}")
                    .when().post("/agents/research")
                    .then().statusCode(403).contentType("application/problem+json").body("status", equalTo(403));
        }
        assertEquals(allThreads, threads(), "a refused call opens no checkpoint thread");
        assertEquals(5, deniedDecisions() - before, "each refusal is an audited policy decision");
    }

    @Test
    void aThreadBelongsToOneUserOfOneTenant() throws Exception {
        String tenantA = "t-" + UlidGenerator.shared().next().value();
        String tenantB = "t-" + UlidGenerator.shared().next().value();
        String thread = newThread();
        // (tenant, user): alice and bob of A, alice of B all send the same visible thread id; alice of A twice
        for (String[] who : new String[][] {{tenantA, "alice"}, {tenantA, "bob"}, {tenantB, "alice"}, {tenantA, "alice"}}) {
            given().header("X-Tenant", who[0]).header("X-Subject", who[1]).header("X-Scopes", RUN)
                    .contentType("application/json")
                    .body("{\"topic\":\"agentic workflows\",\"threadId\":\"" + thread + "\"}")
                    .when().post("/agents/research")
                    .then().statusCode(200).body("threadId", equalTo(thread)).body("plan", containsString("[stub]"));
        }
        // one thread name per owner (the SDK writes one row per run): alice of A ran twice, the others once
        java.util.Map<String, Integer> runs = new java.util.TreeMap<>();
        for (String[] who : new String[][] {{tenantA, "alice"}, {tenantA, "bob"}, {tenantB, "alice"}, {tenantA, "alice"}}) {
            runs.merge(AgentResource.threadOf(who[0], new Subject(SubjectKind.USER, who[1]), "direct:" + thread).value(), 1, Integer::sum);
        }
        assertEquals(3, runs.size(), "three distinct owners derive three distinct threads; the owner's repeat reuses its own");
        for (var e : runs.entrySet()) {
            assertEquals(e.getValue().longValue(), threadRows(e.getKey()), e.getKey());
        }
        assertEquals(0, threadRows(thread), "the bare visible id is never a checkpoint thread");
    }

    @Test
    void aBadBodyIs400OnlyAfterTheCallerAndThePolicyHavePassed() throws Exception {
        String tenant = "t-" + UlidGenerator.shared().next().value();
        for (String body : List.of("{", "", "[]", "{}", "{\"topic\":\"  \"}", "{\"topic\":\"x\",\"threadId\":\"a b\"}",
                "{\"topic\":\"x\",\"threadId\":\"../t\"}")) {
            caller(tenant, RUN).contentType("application/json").body(body).when().post("/agents/research")
                    .then().statusCode(400);
        }
        // the same bodies from an unauthorized caller are 403, never 400: nothing about the body is revealed
        for (String body : List.of("{", "{}")) {
            caller(tenant, "oa.inspect").contentType("application/json").body(body).when().post("/agents/research")
                    .then().statusCode(403);
        }
    }

    private long deniedDecisions() throws SQLException {
        return scalar("select count(*) from audit.event where event_type = 'policy.decision'"
                + " and resource_id = 'agent:research:run' and attributes ->> 'policy.outcome' = 'DENY'");
    }

    @Test
    void theLongestValidSubjectAndEveryKindDeriveDistinctBoundedThreads() {
        String longest = "u".repeat(256);
        var a = AgentResource.threadOf("t", new Subject(SubjectKind.USER, longest), "direct:x");
        var b = AgentResource.threadOf("t", new Subject(SubjectKind.AGENT, longest), "direct:x");
        var c = AgentResource.threadOf("t", new Subject(SubjectKind.USER, longest.substring(1)), "direct:x");
        assertEquals(68, a.value().length());
        assertEquals(3, java.util.Set.of(a, b, c).size(), "kind and id are both part of the owner, nothing is truncated");
    }

    private long threadRows(String name) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var s = c.prepareStatement("select count(*) from lg4jthread where thread_name = ?")) {
            s.setString(1, name);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
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
