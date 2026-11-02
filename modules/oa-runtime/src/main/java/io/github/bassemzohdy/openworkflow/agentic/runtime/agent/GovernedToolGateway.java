package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolGateway;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Explicitly composed local tool boundary. Registration and approval are trusted inputs, never request fields.
 * This class is not a CDI producer, MCP client, durable call budget, timeout executor or effect-ledger integration.
 * A caller must not treat this partial boundary as production tool execution until those controls are supplied.
 */
public final class GovernedToolGateway implements ToolGateway {
    private static final int MAX_JSON = 65_536;
    private static final ObjectMapper JSON = new ObjectMapper();
    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(MAX_JSON).maxNumberLength(1000).build());
        JSON.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JSON.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    /** Authority and remaining budget are supplied by the trusted execution, not by the tool request. */
    public record Approval(Subject agent, AgentTaskContract task, long budgetRemainingMicros) {
        public Approval {
            Objects.requireNonNull(agent); Objects.requireNonNull(task);
            if (budgetRemainingMicros < 0) throw new IllegalArgumentException("negative budget");
        }
    }

    @FunctionalInterface public interface Tool { String invoke(String argumentsJson) throws Exception; }

    /** A tool is registered by trusted composition with fixed schemas and declared side-effect semantics. */
    public static final class Registration {
        private final Schema input;
        private final Schema output;
        private final boolean mutating;
        private final Tool tool;

        public Registration(String inputSchema, String outputSchema, boolean mutating, Tool tool) {
            this.input = schema(inputSchema); this.output = schema(outputSchema);
            this.mutating = mutating; this.tool = Objects.requireNonNull(tool);
        }
    }

    private final Map<String, Registration> registry;
    private final Supplier<Optional<Approval>> approvals;
    private final Pep pep;
    private final Clock clock;

    public GovernedToolGateway(Map<String, Registration> registry, Supplier<Optional<Approval>> approvals,
                               Pep pep, Clock clock) {
        this.registry = Map.copyOf(registry); this.approvals = Objects.requireNonNull(approvals);
        this.pep = Objects.requireNonNull(pep); this.clock = Objects.requireNonNull(clock);
    }

    @Override public ToolResult invoke(ToolCall call) {
        Objects.requireNonNull(call);
        Approval approved = current();
        if (approved == null || !approved.task().id().equals(call.taskId())) return reject("no matching task approval");
        if (!approved.task().permitsTool(call.toolName())) return reject("tool is not permitted by the task");
        if (approved.budgetRemainingMicros() == 0) return reject("no tool budget remaining");
        Registration entry = registry.get(call.toolName());
        if (entry == null) return reject("tool is not registered");
        JsonNode arguments = parse(call.argumentsJson());
        if (arguments == null || !arguments.isObject() || !valid(entry.input, arguments))
            return reject("tool arguments are invalid");
        if (!same(approved)) return reject("tool approval changed");
        final Decision decision;
        try { decision = pep.toolCall(approved.agent(), approved.task(), call.toolName(), approved.budgetRemainingMicros()); }
        catch (RuntimeException failure) { return reject("tool policy is unavailable"); }
        if (!decision.allows()) return new ToolResult.Rejected(decision);
        if (!same(approved)) return reject("tool approval changed");
        final String answer;
        try { answer = entry.tool.invoke(call.argumentsJson()); }
        catch (Exception failure) { return failed(entry); }
        if (!same(approved)) return failed(entry);
        JsonNode output = parse(answer);
        if (output == null || !valid(entry.output, output) || !same(approved)) return failed(entry);
        return new ToolResult.Completed(answer);
    }

    private Approval current() {
        try { return approvals.get().orElse(null); }
        catch (RuntimeException unavailable) { return null; }
    }
    private boolean same(Approval expected) { return expected.equals(current()); }
    private ToolResult.Rejected reject(String reason) {
        return new ToolResult.Rejected(new Decision("tool-gateway", DecisionOutcome.DENY, reason,
                PolicyRef.FAIL_CLOSED, clock.instant()));
    }
    private static ToolResult.Failed failed(Registration entry) {
        return new ToolResult.Failed("the tool call failed or its output was withheld", entry.mutating);
    }
    private static JsonNode parse(String text) {
        if (text == null || text.length() > MAX_JSON) return null;
        try {
            // readTree alone accepts trailing JSON; require exactly one complete value.
            try (var parser = JSON.createParser(text)) {
                JsonNode node = JSON.readTree(parser);
                return node != null && parser.nextToken() == null ? node : null;
            }
        } catch (Exception invalid) { return null; }
    }
    private static boolean valid(Schema schema, JsonNode value) {
        try { return schema.validate(value).isEmpty(); }
        catch (RuntimeException unavailable) { return false; }
    }
    private static Schema schema(String source) {
        JsonNode node = parse(source);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("invalid tool schema");
        requireSelfContained(node);
        try { return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(source); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid tool schema", invalid); }
    }
    private static void requireSelfContained(JsonNode node) {
        if (node.isObject()) {
            for (String key : new String[] {"$ref", "$dynamicRef", "$recursiveRef", "$id", "$schema"})
                if (node.has(key)) throw new IllegalArgumentException("schema references are unsupported");
        }
        node.elements().forEachRemaining(GovernedToolGateway::requireSelfContained);
    }
}
