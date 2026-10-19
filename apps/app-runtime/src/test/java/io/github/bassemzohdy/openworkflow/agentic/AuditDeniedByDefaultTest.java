package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Without a bound policy and without agentic.dev.audit-open, every audit endpoint answers 403 (M15, WP-111). */
@QuarkusTest
class AuditDeniedByDefaultTest {

    @Test
    void listingEventsIsForbidden() {
        given().when().get("/audit/events")
                .then().statusCode(403)
                .contentType("application/problem+json")
                .body("status", equalTo(403));
    }

    @Test
    void readingOneEventIsForbiddenBeforeTheLookup() {
        given().when().get("/audit/events/01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .then().statusCode(403)
                .contentType("application/problem+json");
    }

    @Test
    void verifyingIsForbidden() {
        given().when().post("/audit/verify")
                .then().statusCode(403)
                .contentType("application/problem+json");
    }
}
