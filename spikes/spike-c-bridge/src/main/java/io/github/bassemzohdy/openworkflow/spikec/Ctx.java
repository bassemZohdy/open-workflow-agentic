package io.github.bassemzohdy.openworkflow.spikec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import java.util.Map;

/**
 * The context that has to reach the agent with every call: identity, correlation, deadline and budget.
 * It is a plain record on purpose. The same record is the workflow input, the HTTP body, the Java argument of the
 * in-process bridge and the value stored in the graph state, so each bridge can be compared on what arrives.
 */
public record Ctx(
        String tenant,
        String user,
        String instanceId,
        String correlationId,
        String deadline,
        int budget,
        String topic) {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** Checkpoint thread of this run (ADR 0020, decision 1). */
    public ThreadId threadId() {
        return ThreadId.derive(tenant, user, instanceId);
    }

    public Map<String, Object> asMap() {
        return MAPPER.convertValue(this, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Ctx fromJson(String json) {
        try {
            return MAPPER.readValue(json, Ctx.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored context is not readable: " + json, e);
        }
    }
}
