package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Problem details (RFC 9457) and correlation ids on the running application. */
@QuarkusTest
class ProblemDetailsRestTest {

    @Test
    void unknownPathReturnsProblemJsonAndEchoesTheClientCorrelationId() {
        given().header("X-Correlation-Id", "client-abc-1")
                .when().get("/no/such/path")
                .then().statusCode(404)
                .contentType("application/problem+json")
                .header("X-Correlation-Id", "client-abc-1")
                .body("status", equalTo(404))
                .body("title", equalTo("Not Found"))
                .body("type", equalTo("about:blank"))
                .body("correlationId", equalTo("client-abc-1"));
    }

    @Test
    void unexpectedExceptionBecomesAProblemWithoutInternalDetail() {
        given().when().get("/test/boom")
                .then().statusCode(500)
                .contentType("application/problem+json")
                .header("X-Correlation-Id", notNullValue())
                .body("status", equalTo(500))
                .body("detail", not(containsString("internal detail")))
                .body("correlationId", notNullValue());
    }

    @Test
    void aCorrelationIdIsGeneratedWhenTheClientSendsNone() {
        given().when().get("/no/such/path")
                .then().header("X-Correlation-Id", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}"));
    }

    @Test
    void anUnsafeClientValueIsReplacedNotEchoed() {
        given().header("X-Correlation-Id", "bad<value>")
                .when().get("/no/such/path")
                .then().header("X-Correlation-Id", not(equalTo("bad<value>")))
                .body("correlationId", not(equalTo("bad<value>")));
    }
}
