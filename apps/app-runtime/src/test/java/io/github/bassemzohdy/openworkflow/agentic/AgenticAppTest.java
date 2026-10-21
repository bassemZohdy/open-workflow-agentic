package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.AgentOutputValidator;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
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
    AgentOutputValidator outputValidator;

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
    @Acceptance(value = "T01", requirements = {"M01", "M02", "M12", "M14"})
    void structuredAgentOutputRejectsMalformedActionsAndUnauthorizedEscalation() {
        assertThrows(IllegalArgumentException.class, () -> outputValidator.validateText("not json"));
        assertThrows(IllegalArgumentException.class, () -> outputValidator.validateText(
                "{\"text\":\"ok\",\"actions\":[\"publish\"],\"escalation\":null}"));
        assertThrows(IllegalArgumentException.class, () -> outputValidator.validateText(
                "{\"text\":\"ok\",\"actions\":[],\"escalation\":\"approver\"}"));
    }

    @Test
    void healthIsUp() {
        given().when().get("/q/health/ready").then().statusCode(200);
    }
}
