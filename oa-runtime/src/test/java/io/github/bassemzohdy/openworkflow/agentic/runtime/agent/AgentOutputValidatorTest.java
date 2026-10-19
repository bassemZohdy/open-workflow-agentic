package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentOutputValidatorTest {
    private final AgentOutputValidator validator = new AgentOutputValidator(new ObjectMapper(), target -> false);

    @Test
    void acceptsOnlyStructuredTextWithNoActions() {
        assertEquals("approved", validator.validateText(
                "{\"text\":\"approved\",\"actions\":[],\"escalation\":null}"));
    }

    @Test
    void rejectsMalformedOutputAndWrongFieldTypes() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateText("not json"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateText(
                "{\"text\":12,\"actions\":[],\"escalation\":null}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateText(
                "{\"text\":\"ok\",\"actions\":\"none\",\"escalation\":null}"));
    }

    @Test
    void rejectsForbiddenActionsAndUnauthorizedEscalation() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateText(
                "{\"text\":\"ok\",\"actions\":[\"publish\"],\"escalation\":null}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateText(
                "{\"text\":\"ok\",\"actions\":[],\"escalation\":\"approver\"}"));
    }

    @Test
    void acceptsEscalationOnlyAfterAuthorization() {
        var authorized = new AgentOutputValidator(new ObjectMapper(), target -> target.equals("approver"));
        assertEquals("needs review", authorized.validateText(
                "{\"text\":\"needs review\",\"actions\":[],\"escalation\":\"approver\"}"));
    }
}
