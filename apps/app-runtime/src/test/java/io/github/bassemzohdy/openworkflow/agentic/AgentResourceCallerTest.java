package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
    void anAnonymousCallerIs401WhateverTheBodyAndTheAgentDoesNotRun() throws Exception {
        String thread = newThread();
        for (String body : List.of("{\"topic\":\"x\",\"threadId\":\"" + thread + "\"}", "{", "", "[]")) {
            given().contentType("application/json").body(body).when().post("/agents/research")
                    .then().statusCode(401);
        }
        // a claimed identity without a token is not a caller either (the header seam is trusted in %test, so the claim
        // is only refused for want of X-Tenant/X-Subject: the anonymous case above sends neither)
        assertEquals(0, checkpointRows(thread));
    }

    @Test
    void aCallerWithoutTheScopeIsRefusedAndTheAgentDoesNotRun() throws Exception {
        String tenant = "t-" + UlidGenerator.shared().next().value();
        String thread = newThread();
        long before = deniedDecisions();
        for (String scopes : new String[] {null, "", "oa.inspect", "oa.operate,oa.recover", "oa.agent.runner"}) {
            caller(tenant, scopes).contentType("application/json")
                    .body("{\"topic\":\"x\",\"threadId\":\"" + thread + "\"}")
                    .when().post("/agents/research")
                    .then().statusCode(403).contentType("application/problem+json").body("status", equalTo(403));
        }
        assertEquals(0, checkpointRows(thread));
        assertEquals(5, deniedDecisions() - before, "each refusal is an audited policy decision");
    }

    @Test
    void aCallerWithTheScopeRunsTheAgentOnAThreadPrivateToItsTenant() throws Exception {
        String tenantA = "t-" + UlidGenerator.shared().next().value();
        String tenantB = "t-" + UlidGenerator.shared().next().value();
        String thread = newThread();
        for (String tenant : List.of(tenantA, tenantB)) {
            caller(tenant, RUN).contentType("application/json")
                    .body("{\"topic\":\"agentic workflows\",\"threadId\":\"" + thread + "\"}")
                    .when().post("/agents/research")
                    .then().statusCode(200).body("threadId", equalTo(thread))
                    .body("plan", containsString("[stub]"));
        }
        // the same caller-visible thread id is a different checkpoint thread per tenant
        List<String> threads = checkpointThreads(thread);
        assertEquals(true, threads.contains(tenantA + "/" + thread), threads.toString());
        assertEquals(true, threads.contains(tenantB + "/" + thread), threads.toString());
        assertEquals(false, threads.contains(thread), "the bare id is never a checkpoint thread: " + threads);
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

    private long checkpointRows(String thread) throws SQLException {
        return checkpointThreads(thread).size();
    }

    /** Names of checkpoint threads that end with the given caller-visible thread id (any tenant prefix). */
    private List<String> checkpointThreads(String thread) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                var s = c.prepareStatement("select distinct thread_name from lg4jthread where thread_name like ?")) {
            s.setString(1, "%" + thread);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
        }
        return out;
    }

    private long scalar(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }
}
