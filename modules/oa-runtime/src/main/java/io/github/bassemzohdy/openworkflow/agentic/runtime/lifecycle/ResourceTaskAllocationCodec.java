package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.time.Duration;

/** Strict retained data shape; refuses fields/coercions that a permissive decoder would silently discard. */
final class ResourceTaskAllocationCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ResourceTaskAllocationCodec() { }

    static String contract(AgentTaskContract value) { return AgentTaskCodec.encode(value); }

    static AgentTaskContract contract(String stored) {
        var value = AgentTaskCodec.decode(stored);
        requireSameShape(stored, contract(value));
        return value;
    }

    static String profile(ApprovedModelProfile value) {
        var node = JSON.createObjectNode();
        node.put("id", value.id()).put("version", value.version())
                .put("configurationFingerprint", value.configurationFingerprint());
        node.putObject("agent").put("kind", value.agent().kind().name()).put("id", value.agent().id());
        node.put("modelId", value.modelId()).put("maxModelCalls", value.maxModelCalls())
                .put("perCallCeilingMicros", value.perCallCeilingMicros())
                .put("maxCallSeconds", value.maxCallTime().getSeconds()).put("maxCallNanos", value.maxCallTime().getNano())
                .put("maxOutputTokens", value.maxOutputTokens());
        return node.toString();
    }

    static ApprovedModelProfile profile(String stored) {
        try {
            JsonNode node = JSON.readTree(stored);
            var value = new ApprovedModelProfile(node.get("id").asText(), node.get("version").asText(),
                    node.get("configurationFingerprint").asText(), new Subject(
                    SubjectKind.valueOf(node.path("agent").get("kind").asText()), node.path("agent").get("id").asText()),
                    node.get("modelId").asText(), node.get("maxModelCalls").asInt(), node.get("perCallCeilingMicros").asLong(),
                    Duration.ofSeconds(node.get("maxCallSeconds").asLong(), node.get("maxCallNanos").asLong()),
                    node.get("maxOutputTokens").asInt());
            requireSameShape(stored, profile(value));
            return value;
        } catch (Exception invalid) {
            throw new IllegalStateException("stored model profile is inconsistent", invalid);
        }
    }

    private static void requireSameShape(String stored, String canonical) {
        try {
            if (!JSON.readTree(stored).equals(JSON.readTree(canonical)))
                throw new IllegalStateException("stored allocation shape differs from its data contract");
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("stored allocation data is malformed", invalid);
        }
    }
}
