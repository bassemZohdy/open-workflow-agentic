package io.github.bassemzohdy.openworkflow.agentic.contracts.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Evidence;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The JSON wire form of the contract records, written by hand in the test so the schemas are checked against an
 * independent description rather than against the records themselves. The shape follows the schema files. Nothing
 * here is shipped: the main code has no serialisation (ADR 0017 keeps framework code out of oa-contracts).
 */
final class WireFormat {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WireFormat() {
    }

    static ObjectNode subject(Subject subject) {
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", subject.kind().name());
        node.put("id", subject.id());
        return node;
    }

    static ObjectNode agentTaskContract(AgentTaskContract c) {
        ObjectNode limits = JSON.createObjectNode();
        limits.put("maxIterations", c.limits().maxIterations());
        limits.put("maxWallClockSeconds", c.limits().maxWallClock().toSeconds());
        limits.put("maxCostMicros", c.limits().maxCostMicros());

        ObjectNode node = JSON.createObjectNode();
        node.put("id", c.id().value());
        node.put("goal", c.goal());
        node.set("permittedTools", strings(c.permittedTools()));
        node.set("permittedDataScopes", strings(c.permittedDataScopes()));
        node.put("riskTier", c.riskTier().name());
        node.set("limits", limits);
        node.set("requestingSubject", subject(c.requestingSubject()));
        node.put("delegationGrantId", c.delegationGrantId().value());
        return node;
    }

    static ObjectNode decision(Decision d) {
        ObjectNode policy = JSON.createObjectNode();
        policy.put("policyId", d.policy().policyId());
        policy.put("policySetVersion", d.policy().policySetVersion());

        ObjectNode node = JSON.createObjectNode();
        node.put("decidedBy", d.decidedBy());
        node.put("outcome", d.outcome().name());
        node.put("reason", d.reason());
        node.set("policy", policy);
        node.put("decidedAt", instant(d.decidedAt()));
        return node;
    }

    static ObjectNode effect(Effect e) {
        ObjectNode node = JSON.createObjectNode();
        node.put("threadId", e.threadId().value());
        node.put("nodeId", e.nodeId());
        node.put("idempotencyKey", e.idempotencyKey());
        node.put("target", e.target());
        node.put("state", e.state().name());
        node.put("detail", e.detail());
        node.put("recordedAt", instant(e.recordedAt()));
        return node;
    }

    static ObjectNode humanTask(HumanTask h) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", h.id().value());
        node.put("agentTaskId", h.agentTaskId() == null ? null : h.agentTaskId().value());
        node.put("title", h.title());
        node.put("instructions", h.instructions());
        node.put("status", h.status().name());
        if (h.assignee() == null) {
            node.putNull("assignee");
        } else {
            node.set("assignee", subject(h.assignee()));
        }
        node.put("createdAt", instant(h.createdAt()));
        node.put("dueAt", h.dueAt() == null ? null : instant(h.dueAt()));
        return node;
    }

    static ObjectNode auditEvent(AuditEvent e) {
        ObjectNode attributes = JSON.createObjectNode();
        for (Map.Entry<String, String> entry : new TreeMap<>(e.attributes()).entrySet()) {
            attributes.put(entry.getKey(), entry.getValue());
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("id", e.id().value());
        node.put("sequence", e.sequence());
        node.put("occurredAt", instant(e.occurredAt()));
        node.set("actor", subject(e.actor()));
        node.put("action", e.action());
        node.put("resourceType", e.resourceType());
        node.put("resourceId", e.resourceId());
        node.set("attributes", attributes);
        node.put("previousHash", e.previousHash());
        node.put("hash", e.hash());
        return node;
    }

    static ObjectNode evidence(Evidence e) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", e.id().value());
        node.put("agentTaskId", e.agentTaskId().value());
        node.put("kind", e.kind().name());
        node.put("reference", e.reference());
        node.put("sha256", e.sha256());
        node.put("capturedAt", instant(e.capturedAt()));
        return node;
    }

    private static ArrayNode strings(Set<String> values) {
        ArrayNode array = JSON.createArrayNode();
        new TreeSet<>(values).forEach(array::add);
        return array;
    }

    private static String instant(Instant value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}
