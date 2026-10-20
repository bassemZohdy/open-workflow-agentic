package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * By default the audit API serves nobody (M15, WP-111, WP-102). A request that identifies nobody is 401: there is no
 * anonymous caller, so there is nothing to authorize. An identified caller is still refused (403) while no policy is
 * bound and agentic.dev.audit-open is false; that is {@code audit.AuditCallerDefaultDenyTest}.
 */
@QuarkusTest
class AuditDeniedByDefaultTest {

    @Test
    void listingEventsIsUnauthorized() {
        given().when().get("/audit/events")
                .then().statusCode(401)
                .contentType("application/problem+json")
                .body("status", equalTo(401));
    }

    @Test
    void readingOneEventIsUnauthorizedBeforeTheLookup() {
        given().when().get("/audit/events/01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .then().statusCode(401)
                .contentType("application/problem+json");
    }

    @Test
    void verifyingIsUnauthorized() {
        given().when().post("/audit/verify")
                .then().statusCode(401)
                .contentType("application/problem+json");
    }
}
