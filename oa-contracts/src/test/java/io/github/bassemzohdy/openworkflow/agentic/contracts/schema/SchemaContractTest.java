package io.github.bassemzohdy.openworkflow.agentic.contracts.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Evidence;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks the six JSON Schemas (Draft 2020-12) against documents written by {@link WireFormat}: valid samples pass,
 * and documents that break each rule are rejected.
 */
class SchemaContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Class<?>> RECORDS = new LinkedHashMap<>();

    static {
        RECORDS.put("agent-task-contract.schema.json", AgentTaskContract.class);
        RECORDS.put("decision.schema.json", Decision.class);
        RECORDS.put("effect.schema.json", Effect.class);
        RECORDS.put("human-task.schema.json", HumanTask.class);
        RECORDS.put("audit-event.schema.json", AuditEvent.class);
        RECORDS.put("evidence.schema.json", Evidence.class);
    }

    private static String text(String file) {
        try (InputStream in = SchemaContractTest.class.getResourceAsStream("/schemas/" + file)) {
            if (in == null) {
                throw new IllegalStateException("missing schema resource " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Schema schema(String file) {
        return REGISTRY.getSchema(text(file));
    }

    private static List<Error> errors(String file, JsonNode document) {
        return schema(file).validate(document);
    }

    private static ObjectNode copy(JsonNode document) {
        return ((ObjectNode) document).deepCopy();
    }

    private static void assertValid(String file, JsonNode document) {
        List<Error> errors = errors(file, document);
        assertTrue(errors.isEmpty(), file + " rejected a valid sample: " + errors);
    }

    private static void assertInvalid(String file, JsonNode document, String why) {
        assertFalse(errors(file, document).isEmpty(), file + " accepted an invalid document: " + why);
    }

    // Valid samples

    @Test
    void theSampleRecordsValidateAgainstTheirSchemas() {
        assertValid("agent-task-contract.schema.json", WireFormat.agentTaskContract(Samples.contract()));
        assertValid("decision.schema.json", WireFormat.decision(Samples.decision()));
        assertValid("effect.schema.json", WireFormat.effect(Samples.effect()));
        assertValid("human-task.schema.json", WireFormat.humanTask(Samples.humanTask()));
        assertValid("audit-event.schema.json", WireFormat.auditEvent(Samples.auditEvent()));
        assertValid("evidence.schema.json", WireFormat.evidence(Samples.evidence()));
    }

    @Test
    void optionalFieldsMayBeNullWhereTheRecordAllowsIt() {
        assertValid("effect.schema.json", WireFormat.effect(new Effect(Samples.effect().threadId(), "draft",
                "idem-2", "case-system:update", EffectState.CONFIRMED, "201 created", Samples.NOW)));
        HumanTask done = new HumanTask(Samples.EVIDENCE_ID, null, "t", "i", HumanTaskStatus.COMPLETED,
                Samples.USER, Samples.NOW, null);
        assertValid("human-task.schema.json", WireFormat.humanTask(done));
    }

    // Shape of the schema files

    @Test
    void everySchemaIsDraft202012_closedAtTheTopLevel_andNamesTheRecordFields() throws IOException {
        for (Map.Entry<String, Class<?>> entry : RECORDS.entrySet()) {
            JsonNode root = JSON.readTree(text(entry.getKey()));
            assertEquals("https://json-schema.org/draft/2020-12/schema", root.get("$schema").asText(), entry.getKey());
            assertEquals(false, root.get("additionalProperties").asBoolean(), entry.getKey());
            Set<String> fields = new LinkedHashSet<>();
            root.get("properties").fieldNames().forEachRemaining(fields::add);
            Set<String> components = java.util.Arrays.stream(entry.getValue().getRecordComponents())
                    .map(c -> c.getName()).collect(Collectors.toCollection(LinkedHashSet::new));
            assertEquals(components, fields, entry.getKey() + " must describe exactly the record components");
            assertEquals(components, toSet(root.get("required")), entry.getKey() + " must require every field");
        }
    }

    private static Set<String> toSet(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // Invalid documents

    @Test
    void agentTaskContractRejectsBrokenAuthorityAndLimits() {
        String f = "agent-task-contract.schema.json";
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> n.remove("goal")), "no goal");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> n.put("goal", "   ")),
                "blank goal");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> n.put("riskTier", "CRITICAL")),
                "unknown risk tier");
        assertInvalid(f, limit(0, "maxIterations"), "zero iterations");
        assertInvalid(f, limit(1001, "maxIterations"), "iterations above the maximum");
        assertInvalid(f, limit(86401, "maxWallClockSeconds"), "wall clock above 24 hours");
        assertInvalid(f, limit(0, "maxWallClockSeconds"), "zero wall clock");
        assertInvalid(f, limit(0, "maxCostMicros"), "zero cost");
        assertInvalid(f, limit(1_000_000_000_001L, "maxCostMicros"), "cost above the maximum");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> ((ObjectNode) n.get("limits"))
                .remove("maxCostMicros")), "missing mandatory cost limit");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> n.set("permittedTools",
                JSON.createArrayNode().add("Bad Tool"))), "tool name with a space and capitals");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> n.set("permittedDataScopes",
                JSON.createArrayNode().add("case.read").add("case.read"))), "duplicate scope");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> n.put("owner", "someone")),
                "unknown field");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> n.put("id", "01arz3ndektsv4rrffq69g5fav")),
                "lower-case ulid");
        assertInvalid(f, mutate(WireFormat.agentTaskContract(Samples.contract()), n -> ((ObjectNode) n
                .get("requestingSubject")).put("kind", "ROBOT")), "unknown subject kind");
    }

    @Test
    void decisionRejectsUnknownOutcomesAndMissingVersions() {
        String f = "decision.schema.json";
        assertInvalid(f, mutate(WireFormat.decision(Samples.decision()), n -> n.put("outcome", "MAYBE")),
                "unknown outcome");
        assertInvalid(f, mutate(WireFormat.decision(Samples.decision()), n -> n.put("reason", "")), "empty reason");
        assertInvalid(f, mutate(WireFormat.decision(Samples.decision()), n -> ((ObjectNode) n.get("policy"))
                .remove("policySetVersion")), "no policy-set version");
        assertInvalid(f, mutate(WireFormat.decision(Samples.decision()), n -> n.remove("decidedBy")),
                "no attribution");
    }

    @Test
    void effectRejectsUnknownStatesAndRawThreadNames() {
        String f = "effect.schema.json";
        assertInvalid(f, mutate(WireFormat.effect(Samples.effect()), n -> n.put("state", "DONE")), "unknown state");
        assertInvalid(f, mutate(WireFormat.effect(Samples.effect()), n -> n.put("threadId", "acme|x|y|9")),
                "delimiter-joined thread name");
        assertInvalid(f, mutate(WireFormat.effect(Samples.effect()), n -> n.put("threadId", "ws1-" + "A".repeat(64))),
                "upper-case hex in thread name");
        assertInvalid(f, mutate(WireFormat.effect(Samples.effect()), n -> n.put("detail", "d".repeat(1001))),
                "detail above the limit");
        assertInvalid(f, mutate(WireFormat.effect(Samples.effect()), n -> n.remove("detail")),
                "detail must be present, even when null");
    }

    @Test
    void humanTaskRequiresAnAssigneeWhenInProgressOrCompleted() {
        String f = "human-task.schema.json";
        assertInvalid(f, mutate(WireFormat.humanTask(Samples.humanTask()), n -> n.put("status", "ASSIGNED")),
                "unknown status");
        assertInvalid(f, mutate(WireFormat.humanTask(Samples.humanTask()), n -> n.put("status", "IN_PROGRESS")),
                "in progress without assignee");
        assertInvalid(f, mutate(WireFormat.humanTask(Samples.humanTask()), n -> n.put("status", "COMPLETED")),
                "completed without assignee");
        assertInvalid(f, mutate(WireFormat.humanTask(Samples.humanTask()), n -> n.put("title", " ")), "blank title");
    }

    @Test
    void evidenceNeedsALowerCaseSha256() {
        String f = "evidence.schema.json";
        assertInvalid(f, mutate(WireFormat.evidence(Samples.evidence()), n -> n.put("sha256", "A".repeat(64))),
                "upper-case digest");
        assertInvalid(f, mutate(WireFormat.evidence(Samples.evidence()), n -> n.put("sha256", "a".repeat(63))),
                "short digest");
        assertInvalid(f, mutate(WireFormat.evidence(Samples.evidence()), n -> n.put("kind", "UNKNOWN")),
                "unknown kind");
    }

    @Test
    void auditEventRejectsBrokenChainFieldsAndTooManyAttributes() {
        String f = "audit-event.schema.json";
        assertInvalid(f, mutate(WireFormat.auditEvent(Samples.auditEvent()), n -> n.put("sequence", 0)),
                "sequence below one");
        assertInvalid(f, mutate(WireFormat.auditEvent(Samples.auditEvent()), n -> n.put("sequence", "1")),
                "sequence as text");
        assertInvalid(f, mutate(WireFormat.auditEvent(Samples.auditEvent()), n -> n.put("previousHash", "0".repeat(10))),
                "short previous hash");
        assertInvalid(f, mutate(WireFormat.auditEvent(Samples.auditEvent()), n -> n.remove("hash")), "missing hash");
        assertInvalid(f, mutate(WireFormat.auditEvent(Samples.auditEvent()), n -> ((ObjectNode) n.get("attributes"))
                .put("", "empty key")), "empty attribute key");
        assertInvalid(f, mutate(WireFormat.auditEvent(Samples.auditEvent()), n -> {
            ObjectNode attributes = (ObjectNode) n.get("attributes");
            for (int i = 0; i < 33; i++) {
                attributes.put("k" + i, "v");
            }
        }), "more than 32 attributes");
        assertInvalid(f, mutate(WireFormat.auditEvent(Samples.auditEvent()), n -> ((ObjectNode) n.get("actor"))
                .put("kind", "ROBOT")), "unknown actor kind");
    }

    @Test
    void nestedDefinitionsAreEnforced() {
        // The subject object is a nested definition; the validator must walk into it.
        ObjectNode doc = WireFormat.auditEvent(Samples.auditEvent());
        ((ObjectNode) doc.get("actor")).remove("id");
        assertInvalid("audit-event.schema.json", doc, "nested subject without id");
    }

    // Helpers

    private static ObjectNode mutate(ObjectNode base, Consumer<ObjectNode> change) {
        ObjectNode copy = copy(base);
        change.accept(copy);
        return copy;
    }

    private static ObjectNode limit(Number value, String field) {
        ObjectNode doc = WireFormat.agentTaskContract(Samples.contract());
        ObjectNode limits = (ObjectNode) doc.get("limits");
        if (value instanceof Long l) {
            limits.put(field, l);
        } else {
            limits.put(field, value.intValue());
        }
        return doc;
    }

}
