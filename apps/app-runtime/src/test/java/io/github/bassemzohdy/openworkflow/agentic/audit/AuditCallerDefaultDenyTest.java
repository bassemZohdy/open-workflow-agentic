package io.github.bassemzohdy.openworkflow.agentic.audit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Authentication comes before the access rule (WP-102). With the development header seam on, no policy bound and no
 * {@code agentic.dev.audit-open}, a caller who identifies itself is refused with 403 (default deny), and a request that
 * identifies nobody is 401. The 403 is therefore about the caller's rights, never about a missing identity.
 */
@QuarkusTest
@TestProfile(AuditCallerDefaultDenyTest.SeamOnly.class)
class AuditCallerDefaultDenyTest {

    /** Trusts the development headers and nothing else: no policy, and the dev-open flag stays false. */
    public static class SeamOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("agentic.dev.trust-caller-headers", "true");
        }
    }

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
}
