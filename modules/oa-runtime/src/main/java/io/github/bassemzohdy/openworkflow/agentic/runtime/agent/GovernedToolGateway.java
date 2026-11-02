package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolGateway;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Explicitly composed local tool boundary. Registration and approval are trusted inputs, never request fields.
 * This class is not a CDI producer, MCP client, durable call budget or effect-ledger integration.
 * Approval lookups must work on the caller and worker threads; a missing worker context refuses before the tool.
 * A caller must not treat this partial boundary as production tool execution until those controls are supplied.
 */
public final class GovernedToolGateway implements ToolGateway {
    private static final int MAX_JSON = 65_536;
    /** No queue: a stuck tool retains its slot, limiting the number of unfinished physical calls. */
    static final int MAX_IN_FLIGHT = 8;
    private static final Semaphore WORKER_SLOTS = new Semaphore(MAX_IN_FLIGHT);
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

    /** Runs on a bounded worker; credentials and context must be explicitly bound, not read from caller thread locals. */
    @FunctionalInterface public interface Tool { String invoke(String argumentsJson) throws Exception; }

    /** A tool is registered by trusted composition with fixed schemas and declared side-effect semantics. */
    public static final class Registration {
        private final Schema input;
        private final Schema output;
        private final boolean mutating;
        private final Tool tool;
        private final Duration timeout;

        public Registration(String inputSchema, String outputSchema, boolean mutating, Duration timeout, Tool tool) {
            this.input = schema(inputSchema); this.output = schema(outputSchema);
            this.mutating = mutating; this.tool = Objects.requireNonNull(tool);
            this.timeout = Objects.requireNonNull(timeout);
            if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(1)) > 0)
                throw new IllegalArgumentException("tool timeout must be between 1 ms and 1 minute");
        }
    }

    private final Map<String, Registration> registry;
    private final Supplier<Optional<Approval>> approvals;
    private final Pep pep;
    private final Clock clock;
    private final AuditSink audit;
    private static final Subject GATEWAY = new Subject(SubjectKind.SERVICE, "tool-gateway");

    public GovernedToolGateway(Map<String, Registration> registry, Supplier<Optional<Approval>> approvals,
                               Pep pep, AuditSink audit, Clock clock) {
        this.registry = Map.copyOf(registry); this.approvals = Objects.requireNonNull(approvals);
        this.pep = Objects.requireNonNull(pep); this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public ToolResult invoke(ToolCall call) {
        Objects.requireNonNull(call);
        String attempt = UlidGenerator.shared().next().value();
        Approval approved = current();
        if (approved == null || !approved.task().id().equals(call.taskId())) return reject(call, null, attempt, "no matching task approval");
        if (!approved.task().permitsTool(call.toolName())) return reject(call, approved, attempt, "tool is not permitted by the task");
        if (approved.budgetRemainingMicros() == 0) return reject(call, approved, attempt, "no tool budget remaining");
        Registration entry = registry.get(call.toolName());
        if (entry == null) return reject(call, approved, attempt, "tool is not registered");
        JsonNode arguments = parse(call.argumentsJson());
        if (arguments == null || !arguments.isObject() || !valid(entry.input, arguments))
            return reject(call, approved, attempt, "tool arguments are invalid");
        if (!same(approved)) return reject(call, approved, attempt, "tool approval changed");
        final Decision decision;
        try { decision = pep.toolCall(approved.agent(), approved.task(), call.toolName(), approved.budgetRemainingMicros()); }
        catch (RuntimeException failure) { return reject(call, approved, attempt, "tool policy is unavailable"); }
        if (!decision.allows()) {
            record(call, approved, attempt, "agent.tool.refused");
            return new ToolResult.Rejected(decision);
        }
        if (!same(approved)) return reject(call, approved, attempt, "tool approval changed");
        if (!record(call, approved, attempt, "agent.tool.started"))
            return reject(call, approved, attempt, "tool start audit is unavailable");
        if (!same(approved)) return reject(call, approved, attempt, "tool approval changed");
        if (Thread.currentThread().isInterrupted()) return reject(call, approved, attempt, "tool caller was interrupted");
        var mayStart = new java.util.concurrent.atomic.AtomicBoolean(true);
        if (!WORKER_SLOTS.tryAcquire()) return reject(call, approved, attempt, "tool capacity is unavailable");
        var future = new FutureTask<String>(() -> {
            if (!same(approved) || !mayStart.compareAndSet(true, false)) throw new ApprovalExpired();
            return entry.tool.invoke(call.argumentsJson());
        });
        try {
            // A new thread prevents ordinary thread-local values from leaking between tool calls; inheritable caller
            // locals are disabled too. The slot belongs to the physical worker, not the cancellable Future.
            Thread.ofPlatform().name("governed-tool").daemon(true).inheritInheritableThreadLocals(false)
                    .unstarted(() -> { try { future.run(); } finally { WORKER_SLOTS.release(); } }).start();
        } catch (RuntimeException cannotStart) {
            WORKER_SLOTS.release();
            return reject(call, approved, attempt, "tool worker could not start");
        } catch (Error cannotStart) {
            WORKER_SLOTS.release();
            throw cannotStart;
        }
        final String answer;
        try { answer = future.get(entry.timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) {
            mayStart.set(false);
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failed(call, approved, attempt, entry);
        } catch (TimeoutException timedOut) {
            mayStart.set(false);
            future.cancel(true);
            return failed(call, approved, attempt, entry);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ApprovalExpired)
                return reject(call, approved, attempt, "tool approval changed before execution");
            return failed(call, approved, attempt, entry);
        }
        if (!same(approved)) return failed(call, approved, attempt, entry);
        JsonNode output = parse(answer);
        if (output == null || !valid(entry.output, output) || !same(approved)) return failed(call, approved, attempt, entry);
        if (!record(call, approved, attempt, "agent.tool.completed") || !same(approved))
            return failed(call, approved, attempt, entry);
        return new ToolResult.Completed(answer);
    }

    private Approval current() {
        try { return approvals.get().orElse(null); }
        catch (RuntimeException unavailable) { return null; }
    }
    private boolean same(Approval expected) { return expected.equals(current()); }
    private static final class ApprovalExpired extends Exception { }
    private ToolResult.Rejected reject(ToolCall call, Approval approval, String attempt, String reason) {
        record(call, approval, attempt, "agent.tool.refused");
        return new ToolResult.Rejected(new Decision("tool-gateway", DecisionOutcome.DENY, reason,
                PolicyRef.FAIL_CLOSED, clock.instant()));
    }
    private ToolResult.Failed failed(ToolCall call, Approval approved, String attempt, Registration entry) {
        record(call, approved, attempt, "agent.tool.failed");
        return new ToolResult.Failed("the tool call failed or its output was withheld", entry.mutating);
    }
    /** Best-effort for refusal/failure, mandatory for start and completion. No arguments or outputs are recorded. */
    private boolean record(ToolCall call, Approval approval, String attempt, String action) {
        try {
            audit.record(AuditDraft.of(UlidGenerator.shared().next(), clock.instant(),
                    approval == null ? GATEWAY : approval.agent(), action, "agent-tool", "tool:" + call.toolName(),
                    Map.of("agent.task", call.taskId().value(), "agent.tool.attempt", attempt)));
            return true;
        } catch (RuntimeException unavailable) { return false; }
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
        try { return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()))
                .getSchema(source); }
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
