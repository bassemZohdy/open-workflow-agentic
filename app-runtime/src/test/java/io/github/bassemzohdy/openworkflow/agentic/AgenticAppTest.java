package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowDefinition;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AgenticAppTest {

    @Inject
    @Any
    Instance<WorkflowDefinition> definitions;

    @Test
    void owsDefinitionIsLoaded() {
        assertFalse(definitions.isUnsatisfied(), "no OWS definition was registered from src/main/resources/flow");
        var names = definitions.stream().map(d -> d.workflow().getDocument().getName()).toList();
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("research-and-approve"), names.toString());
    }

    @Test
    void agentGraphRunsAndCheckpoints() {
        given().contentType("application/json").body("{\"topic\":\"agentic workflows\"}")
                .when().post("/agents/research")
                .then().statusCode(200)
                .body("threadId", notNullValue())
                .body("plan", containsString("[stub]"))
                .body("review", containsString("[stub]"));
    }

    @Test
    void healthIsUp() {
        given().when().get("/q/health/ready").then().statusCode(200);
    }
}
