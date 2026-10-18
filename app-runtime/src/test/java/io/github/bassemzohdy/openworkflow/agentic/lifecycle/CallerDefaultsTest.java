package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * With the default configuration no caller can be established, so no instance endpoint acts for anyone. This is the
 * "nothing is open by accident" rule: the caller headers are ignored unless the development flag is on.
 */
@QuarkusTest
class CallerDefaultsTest {

    @Inject
    CallerContextResolver callers;

    @Test
    void theDevelopmentHeadersAreNotTrustedByDefault() {
        assertFalse(callers.trustsCallerHeaders());
    }

    @Test
    void startingWithHeadersIsStillRefusedByDefault() {
        given().header("X-Tenant", "t1").header("X-Subject", "alice")
                .contentType("application/json")
                .body(LifecycleSupport.standardBody("default-key"))
                .when().post("/instances")
                .then()
                .statusCode(401)
                .contentType("application/problem+json")
                .body("status", equalTo(401))
                .body("correlationId", notNullValue());
    }

    @Test
    void readingWithHeadersIsRefusedByDefault() {
        given().header("X-Tenant", "t1").header("X-Subject", "alice")
                .when().get("/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .then()
                .statusCode(401);
    }
}
