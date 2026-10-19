package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;

/**
 * The stored JSON form of an {@link AgentTaskContract}. The shape is the wire form of
 * {@code schemas/agent-task-contract.schema.json}: limits as {@code maxWallClockSeconds}, lists sorted so that equal
 * contracts have equal text.
 */
final class AgentTaskCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTaskCodec() {
    }

    static String encode(AgentTaskContract contract) {
        Duration wallClock = contract.limits().maxWallClock();
        if (wallClock.getNano() != 0) {
            throw new IllegalArgumentException("maxWallClock must be a whole number of seconds to be stored");
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", contract.id().value());
        node.put("goal", contract.goal());
        node.set("permittedTools", sorted(contract.permittedTools()));
        node.set("permittedDataScopes", sorted(contract.permittedDataScopes()));
        node.put("riskTier", contract.riskTier().name());
        ObjectNode limits = node.putObject("limits");
        limits.put("maxIterations", contract.limits().maxIterations());
        limits.put("maxWallClockSeconds", wallClock.getSeconds());
        limits.put("maxCostMicros", contract.limits().maxCostMicros());
        ObjectNode subject = node.putObject("requestingSubject");
        subject.put("kind", contract.requestingSubject().kind().name());
        subject.put("id", contract.requestingSubject().id());
        node.put("delegationGrantId", contract.delegationGrantId().value());
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("contract cannot be written", e);
        }
    }

    static AgentTaskContract decode(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            JsonNode limits = node.get("limits");
            ExecutionLimits executionLimits = new ExecutionLimits(
                    limits.get("maxIterations").asInt(),
                    Duration.ofSeconds(limits.get("maxWallClockSeconds").asLong()),
                    limits.get("maxCostMicros").asLong());
            JsonNode subject = node.get("requestingSubject");
            return new AgentTaskContract(
                    Ulid.parse(node.get("id").asText()),
                    node.get("goal").asText(),
                    strings(node.get("permittedTools")),
                    strings(node.get("permittedDataScopes")),
                    RiskTier.valueOf(node.get("riskTier").asText()),
                    executionLimits,
                    new Subject(SubjectKind.valueOf(subject.get("kind").asText()), subject.get("id").asText()),
                    Ulid.parse(node.get("delegationGrantId").asText()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored contract is not valid JSON", e);
        }
    }

    private static ArrayNode sorted(Set<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        new TreeSet<>(values).forEach(array::add);
        return array;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }
}
