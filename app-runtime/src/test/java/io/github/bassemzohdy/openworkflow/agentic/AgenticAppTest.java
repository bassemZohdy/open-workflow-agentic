package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
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
    void workflowInputSchemaRejectsMissingAndWronglyTypedRequiredInput() {
        WorkflowDefinition definition = definitions.stream()
                .filter(d -> "research-and-approve".equals(d.workflow().getDocument().getName()))
                .findFirst().orElseThrow();
        assertThrows(WorkflowException.class, () -> definition.instance(Map.of()),
                "the required topic must be rejected before the workflow starts");
        assertThrows(WorkflowException.class, () -> definition.instance(Map.of("topic", 42)),
                "topic must conform to the declared string type");
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
