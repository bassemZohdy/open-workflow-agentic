package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates model output before it enters checkpointed agent state.
 * The current research task permits text only; actions are therefore always forbidden.
 */
@ApplicationScoped
public class AgentOutputValidator {
    private final ObjectMapper mapper;
    private final EscalationAuthorizer escalationAuthorizer;

    @Inject
    public AgentOutputValidator(ObjectMapper mapper, EscalationAuthorizer escalationAuthorizer) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.escalationAuthorizer = Objects.requireNonNull(escalationAuthorizer, "escalationAuthorizer");
    }

    public String validateText(String raw) {
        final JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Agent output must be a valid JSON object", e);
        }
        if (root == null || !root.isObject() || root.size() != 3
                || !root.has("text") || !root.has("actions") || !root.has("escalation")) {
            throw new IllegalArgumentException("Agent output must contain exactly text, actions, and escalation");
        }
        JsonNode text = root.get("text");
        if (!text.isTextual() || text.textValue().isBlank()) {
            throw new IllegalArgumentException("Agent output text must be a non-blank string");
        }
        JsonNode actions = root.get("actions");
        if (!actions.isArray()) {
            throw new IllegalArgumentException("Agent output actions must be an array");
        }
        List<String> proposedActions = new ArrayList<>();
        for (JsonNode action : actions) {
            if (!action.isTextual() || action.textValue().isBlank()) {
                throw new IllegalArgumentException("Each proposed action must be a non-blank string");
            }
            proposedActions.add(action.textValue());
        }
        if (!proposedActions.isEmpty()) {
            throw new IllegalArgumentException("Agent output contains forbidden actions: " + proposedActions);
        }
        JsonNode escalation = root.get("escalation");
        if (!escalation.isNull()) {
            if (!escalation.isTextual() || escalation.textValue().isBlank()) {
                throw new IllegalArgumentException("Agent output escalation must be null or a non-blank target");
            }
            if (!escalationAuthorizer.isAuthorized(escalation.textValue())) {
                throw new IllegalArgumentException("Agent output requested an unauthorized escalation");
            }
        }
        return text.textValue();
    }
}
