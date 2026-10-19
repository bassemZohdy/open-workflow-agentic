package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The production profile (the default configuration) does not trust the development headers. A scope claim in an
 * unauthenticated request is therefore never read: every operation is refused with 401 and nothing is audited.
 */
@QuarkusTest
class OperationsCallerDefaultsTest {

    @Inject
    DataSource dataSource;

    @Test
    void aScopeClaimInAnUntrustedHeaderIsIgnoredAndEveryOperationIs401() {
        String id = UlidGenerator.shared().next().value();
        given().header("X-Tenant", "t1").header("X-Subject", "ops-lead")
                .header("X-Scopes", "oa.inspect,oa.operate,oa.recover")
                .when().get("/operations/instances/" + id)
                .then().statusCode(401).contentType("application/problem+json")
                .body("status", equalTo(401)).body("correlationId", notNullValue());

        given().header("X-Tenant", "t1").header("X-Subject", "ops-lead")
                .header("X-Scopes", "oa.operate,oa.recover").header("Idempotency-Key", "k-1")
                .when().post("/operations/instances/" + id + "/cancel")
                .then().statusCode(401);

        assertEquals(0, OperationsSupport.auditEvents(dataSource, id), "a refused caller writes no audit event");
    }
}
