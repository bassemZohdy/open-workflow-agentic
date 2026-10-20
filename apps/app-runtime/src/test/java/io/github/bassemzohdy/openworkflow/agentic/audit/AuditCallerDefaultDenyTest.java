package io.github.bassemzohdy.openworkflow.agentic.audit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * Authentication comes before the access rule (WP-102). With the development header seam on, no policy bound and no
 * {@code agentic.dev.audit-open}, a caller who identifies itself is refused with 403 (default deny), and a request that
 * identifies nobody is 401. The 403 is therefore about the caller's rights, never about a missing identity.
 */
@QuarkusTest
@TestProfile(io.github.bassemzohdy.openworkflow.agentic.lifecycle.TrustedCallerProfile.class)
class AuditCallerDefaultDenyTest {

    private static io.restassured.specification.RequestSpecification caller() {
        return given().header("X-Tenant", "audit-tenant").header("X-Subject", "auditor");
    }

    @Test
    void anIdentifiedCallerIsDeniedByDefaultOnEveryEndpoint() {
        caller().when().get("/audit/events")
                .then().statusCode(403).contentType("application/problem+json").body("status", equalTo(403));
        caller().when().get("/audit/events/01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .then().statusCode(403).contentType("application/problem+json");
        caller().when().post("/audit/verify")
                .then().statusCode(403).contentType("application/problem+json");
    }

    @Test
    void aRequestThatIdentifiesNobodyIs401EvenWhenTheSeamIsOn() {
        given().when().get("/audit/events")
                .then().statusCode(401).contentType("application/problem+json").body("status", equalTo(401));
        given().header("X-Tenant", "audit-tenant").when().get("/audit/events")
                .then().statusCode(401);
        given().when().post("/audit/verify").then().statusCode(401);
    }

    // ---- WP-103: the access decision goes through the shared policy enforcement point -------------------------------------

    @jakarta.inject.Inject
    javax.sql.DataSource dataSource;

    private static io.restassured.specification.RequestSpecification scoped(String subject, String scopes) {
        var spec = given().header("X-Tenant", "audit-tenant").header("X-Subject", subject);
        return scopes == null ? spec : spec.header("X-Scopes", scopes);
    }

    private long decisions(String subject, String resource, String outcome) throws SQLException {
        try (Connection c = dataSource.getConnection(); var s = c.prepareStatement(
                "select count(*) from audit.event where event_type = 'policy.decision' and resource_id = ?"
                        + " and actor_id = ? and attributes ->> 'policy.outcome' = ?")) {
            s.setString(1, resource);
            s.setString(2, subject);
            s.setString(3, outcome);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    @Test
    void theReadScopeReadsAndEachScopeCoversOnlyItsOwnAction() throws Exception {
        String user = "auditor-" + System.nanoTime();
        scoped(user, "oa.audit.read").when().get("/audit/events").then().statusCode(200);
        // the read scope does not verify, and the verify scope does not read
        scoped(user, "oa.audit.read").when().post("/audit/verify").then().statusCode(403);
        scoped(user, "oa.audit.verify").when().get("/audit/events").then().statusCode(403);
        scoped(user, "oa.audit.verify").when().post("/audit/verify").then().statusCode(200);
        // no scope, wrong scope
        scoped(user, null).when().get("/audit/events").then().statusCode(403);
        scoped(user, "oa.inspect,oa.operate").when().get("/audit/events/01ARZ3NDEKTSV4RRFFQ69G5FAV").then()
                .statusCode(403);

        assertEquals(1, decisions(user, "audit.events.read", "ALLOW"));
        assertEquals(1, decisions(user, "audit.chains.verify", "ALLOW"));
        assertEquals(4, decisions(user, "audit.events.read", "DENY") + decisions(user, "audit.chains.verify", "DENY"),
                "every refusal is an audit event too");
    }
}
