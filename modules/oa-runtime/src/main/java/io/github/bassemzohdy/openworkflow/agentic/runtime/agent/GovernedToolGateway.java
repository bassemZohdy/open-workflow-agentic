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
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceToolCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.Running;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ApprovedToolProfile;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
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
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.DurableEffects;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.EffectAuditHistory;
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
 * A composed budget must be durable in production. This class is not a CDI producer, MCP client, task-start
 * authority, or production effect-ledger wiring.
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

    /** Credentials and context must be explicitly bound. Legacy calls use a worker; controlled calls are synchronous. */
    @FunctionalInterface public interface Tool { String invoke(String argumentsJson) throws Exception; }

    /** Trusted local binding only: no I/O or side effects; the target enforces finite transport deadlines. */
    @FunctionalInterface public interface EffectTargetFactory { EffectTarget bind(String validatedArgumentsJson); }

    /** A trusted durable budget must commit each admission and recheck its deadline independently. */
    public interface ToolBudget {
        void admit(Approval approval);
        void requireCurrent(Approval approval);
        /** An unprofiled budget never authorizes a gateway call. */
        default void requireProfile(Approval approval, ApprovedToolProfile profile) {
            throw new NodeRefused("retained tool profile is unavailable");
        }
    }

    /** A tool is registered by trusted composition with fixed schemas and declared side-effect semantics. */
    public static final class Registration {
        private final Schema input;
        private final Schema output;
        private final boolean mutating;
        private final Tool tool;
        private final Duration timeout;
        private final String inputSource;
        private final String outputSource;
        private final String implementationFingerprint;
        private String effectTarget;
        private EffectTargetFactory effectFactory;
        private boolean controlledReadOnly;

        public Registration(String inputSchema, String outputSchema, boolean mutating, Duration timeout,
                            String implementationFingerprint, Tool tool) {
            this.input = schema(inputSchema); this.output = schema(outputSchema);
            this.inputSource = inputSchema; this.outputSource = outputSchema;
            this.implementationFingerprint = Checks.requireMatching("tool implementation fingerprint", implementationFingerprint,
                    java.util.regex.Pattern.compile("[0-9a-f]{64}"));
            this.mutating = mutating; this.tool = Objects.requireNonNull(tool);
            this.timeout = Objects.requireNonNull(timeout);
            if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(1)) > 0)
                throw new IllegalArgumentException("tool timeout must be between 1 ms and 1 minute");
        }

        /** Trusted read-only transport with a finite, independently enforced network deadline. */
        public static Registration controlledReadOnly(String inputSchema, String outputSchema, Duration timeout,
                                                       String implementationFingerprint, Tool tool) {
            var registration = new Registration(inputSchema, outputSchema, false, timeout, implementationFingerprint, tool);
            registration.controlledReadOnly = true;
            return registration;
        }

        /** Binds an effect target chosen by trusted composition, never by a tool request. */
        public static Registration effectful(String inputSchema, String outputSchema, Duration timeout,
                                             String implementationFingerprint, String target, EffectTargetFactory factory) {
            var registration = new Registration(inputSchema, outputSchema, true, timeout, implementationFingerprint,
                    args -> { throw new IllegalStateException("effect tools require controlled execution"); });
            registration.effectTarget = Checks.requireMatching("effect target", target, AgentTaskContract.TOKEN);
            registration.effectFactory = Objects.requireNonNull(factory);
            return registration;
        }
    }

    private final Map<String, Registration> registry;
    private final Supplier<Optional<Approval>> approvals;
    private final Pep pep;
    private final Clock clock;
    private final AuditSink audit;
    private final ToolBudget budget;
    private final ApprovedToolProfile profile;
    private final ControlledToolEffects controlledEffects;
    private final ToolCallTelemetry telemetry;
    private static final Subject GATEWAY = new Subject(SubjectKind.SERVICE, "tool-gateway");

    public GovernedToolGateway(Map<String, Registration> registry, Supplier<Optional<Approval>> approvals,
                               ApprovedToolProfile profile, Pep pep, AuditSink audit, ToolBudget budget, Clock clock) {
        this(registry, approvals, profile, pep, audit, budget, clock, null);
    }

    /** Effect execution is enabled only by trusted composition, with the same policy and approval source. */
    public GovernedToolGateway(Map<String, Registration> registry, Supplier<Optional<Approval>> approvals,
                               ApprovedToolProfile profile, Pep pep, AuditSink audit, ToolBudget budget, Clock clock,
                               DurableEffects ledger) {
        this(registry, approvals, profile, pep, audit, budget, clock, ledger, ToolCallTelemetry.noop());
    }

    /** Explicit tracing is optional and is not an approval or execution capability. */
    public GovernedToolGateway(Map<String, Registration> registry, Supplier<Optional<Approval>> approvals,
                               ApprovedToolProfile profile, Pep pep, AuditSink audit, ToolBudget budget, Clock clock,
                               DurableEffects ledger, ToolCallTelemetry telemetry) {
        this(registry,approvals,profile,pep,audit,budget,clock,ledger,telemetry,null,false);
    }

    /** Explicit trusted composition with resource-scoped audit gating; no default production registration. */
    public GovernedToolGateway(Map<String, Registration> registry, Supplier<Optional<Approval>> approvals,
                               ApprovedToolProfile profile, Pep pep, AuditSink audit, ToolBudget budget, Clock clock,
                               DurableEffects ledger, ToolCallTelemetry telemetry,EffectAuditHistory history) {
        this(registry,approvals,profile,pep,audit,budget,clock,Objects.requireNonNull(ledger),telemetry,
                Objects.requireNonNull(history),true);
    }

    private GovernedToolGateway(Map<String, Registration> registry, Supplier<Optional<Approval>> approvals,
                                ApprovedToolProfile profile, Pep pep, AuditSink audit, ToolBudget budget, Clock clock,
                                DurableEffects ledger,ToolCallTelemetry telemetry,EffectAuditHistory history,boolean audited) {
        this.registry = Map.copyOf(registry); this.approvals = Objects.requireNonNull(approvals);
        this.profile = Objects.requireNonNull(profile);
        if (!configurationFingerprint(this.registry).equals(profile.configurationFingerprint()))
            throw new IllegalArgumentException("registered tool configuration differs from profile");
        this.pep = Objects.requireNonNull(pep); this.audit = Objects.requireNonNull(audit);
        this.budget = Objects.requireNonNull(budget); this.clock = Objects.requireNonNull(clock);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.controlledEffects = ledger == null ? null : audited
                ? new ControlledToolEffects(ledger,this.pep,this.approvals,history)
                : new ControlledToolEffects(ledger,this.pep,this.approvals);
    }

    /** Fingerprints exact schemas, timeouts, declared side effects, target IDs and composition-supplied digests.
     * Version 3 changes every fingerprint; existing profiles must be reapproved, not silently migrated.
     */
    public static String configurationFingerprint(Map<String, Registration> registry) {
        var entries = Map.copyOf(registry);
        if (entries.size() > AgentTaskContract.MAX_ALLOW_LIST_SIZE) throw new IllegalArgumentException("too many registered tools");
        var encoder = new CanonicalEncoder("oa.tool.registry.v3").number(entries.size());
        entries.keySet().stream().sorted().forEach(name -> {
            Checks.requireMatching("tool name", name, AgentTaskContract.TOKEN);
            var entry = entries.get(name);
            encoder.text(name).text(entry.inputSource).text(entry.outputSource).text(entry.implementationFingerprint)
                    .number(entry.mutating ? 1 : 0).number(entry.timeout.getSeconds()).number(entry.timeout.getNano())
                    .text(entry.effectTarget == null ? "" : entry.effectTarget).number(entry.controlledReadOnly ? 1 : 0);
        });
        return CanonicalEncoder.sha256Hex(encoder.toBytes());
    }

    @Override public ToolResult invoke(ToolCall call) {
        Objects.requireNonNull(call);
        String attempt = UlidGenerator.shared().next().value();
        Approval approved = current();
        if (approved == null || !approved.task().id().equals(call.taskId())) return reject(call, null, attempt, "no matching task approval");
        if (!approved.task().permitsTool(call.toolName())) return reject(call, approved, attempt, "tool is not permitted by the task");
        if (approved.budgetRemainingMicros() == 0) return reject(call, approved, attempt, "no tool budget remaining");
        try { profile.requireWithin(approved.task(), approved.agent()); }
        catch (IllegalArgumentException mismatch) { return reject(call, approved, attempt, "tool profile differs from task"); }
        if (!profile.tools().contains(call.toolName())) return reject(call, approved, attempt, "tool is not permitted by the profile");
        Registration entry = registry.get(call.toolName());
        if (entry == null) return reject(call, approved, attempt, "tool is not registered");
        if (entry.mutating || entry.controlledReadOnly)
            return reject(call, approved, attempt, "tool requires controlled execution");
        if (entry.timeout.compareTo(profile.maxCallTime()) > 0) return reject(call, approved, attempt, "tool timeout exceeds profile");
        JsonNode arguments = parse(call.argumentsJson());
        if (arguments == null || !arguments.isObject() || !valid(entry.input, arguments))
            return reject(call, approved, attempt, "tool arguments are invalid");
        if (!same(approved) || !budgetCurrent(approved) || !same(approved))
            return reject(call, approved, attempt, "tool approval or profile is unavailable");
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
        try { budget.admit(approved); }
        catch (RuntimeException unavailable) { return reject(call, approved, attempt, "tool budget exhausted or unavailable"); }
        if (!same(approved) || !budgetCurrent(approved) || !same(approved))
            return reject(call, approved, attempt, "tool approval or budget changed");
        var mayStart = new java.util.concurrent.atomic.AtomicBoolean(true);
        // Carry only the span identity across the isolated worker; baggage and arbitrary caller locals stay behind.
        io.opentelemetry.context.Context trace;
        try { trace = io.opentelemetry.context.Context.root().with(io.opentelemetry.api.trace.Span.wrap(
                io.opentelemetry.api.trace.Span.current().getSpanContext())); }
        catch (RuntimeException unavailable) { trace = io.opentelemetry.context.Context.root(); }
        if (!WORKER_SLOTS.tryAcquire()) return reject(call, approved, attempt, "tool capacity is unavailable");
        var workerTrace = trace;
        var future = new FutureTask<String>(() -> {
            io.opentelemetry.context.Scope scope = null;
            try {
                try { scope = workerTrace.makeCurrent(); } catch (RuntimeException ignored) { }
                return telemetry.call(() -> {
                    if (!same(approved) || !budgetCurrent(approved) || !same(approved)
                            || !mayStart.compareAndSet(true, false)) throw new ApprovalExpired();
                    return entry.tool.invoke(call.argumentsJson());
                });
            } finally { if (scope != null) try { scope.close(); } catch (RuntimeException ignored) { } }
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
        if (!same(approved) || !budgetCurrent(approved) || !same(approved)) return failed(call, approved, attempt, entry);
        JsonNode output = parse(answer);
        if (output == null || !valid(entry.output, output) || !same(approved)) return failed(call, approved, attempt, entry);
        if (!record(call, approved, attempt, "agent.tool.completed") || !same(approved)
                || !budgetCurrent(approved) || !same(approved))
            return failed(call, approved, attempt, entry);
        return new ToolResult.Completed(answer);
    }

    /**
     * Synchronous controlled read-only path. The registered callback must enforce its own finite transport
     * deadline and may not perform external effects. The gateway withholds a late answer but cannot interrupt a
     * transport which violates that contract. The caller must keep the enclosing execution alive until return and
     * persist its own occurrence/request binding; read-only results are not durably recorded by this method.
     */
    public ToolResult invokeReadOnly(Running control, ResourceExecutionEntry execution, AllocatedResourceTask allocation,
                                     ResourceToolCall occurrence, ToolCall call) {
        Objects.requireNonNull(control); Objects.requireNonNull(execution); Objects.requireNonNull(allocation);
        Objects.requireNonNull(occurrence); Objects.requireNonNull(call);
        long started = System.nanoTime();
        String attempt = UlidGenerator.shared().next().value();
        control.requireCurrent(execution, occurrence.executor());
        Approval approved = current();
        control.requireCurrent(execution, occurrence.executor());
        if (approved == null || !approved.task().id().equals(call.taskId()) || approved.budgetRemainingMicros() == 0)
            return reject(call, approved, attempt, "no matching tool approval");
        try { profile.requireWithin(approved.task(), approved.agent()); }
        catch (IllegalArgumentException mismatch) { return reject(call, approved, attempt, "tool profile differs from task"); }
        if (!approved.task().permitsTool(call.toolName()) || !profile.tools().contains(call.toolName()))
            return reject(call, approved, attempt, "tool is not permitted");
        Registration registration = registry.get(call.toolName());
        if (registration == null || !registration.controlledReadOnly || registration.mutating
                || registration.timeout.compareTo(profile.maxCallTime()) > 0)
            return reject(call, approved, attempt, "controlled read-only tool is not registered");
        JsonNode arguments = parse(call.argumentsJson());
        if (arguments == null || !arguments.isObject() || !valid(registration.input, arguments))
            return reject(call, approved, attempt, "tool arguments are invalid");
        var expected = ResourceToolCall.of(allocation, profile, execution, occurrence.executor(), occurrence.nodeOccurrence(), call);
        if (!expected.equals(occurrence) || !approved.task().equals(allocation.allocation().contract()))
            return reject(call, approved, attempt, "tool occurrence does not match the request");
        requireControlledCurrent(control, execution, occurrence, approved);
        final Decision decision;
        try { decision = pep.toolCall(approved.agent(), approved.task(), call.toolName(), approved.budgetRemainingMicros()); }
        catch (RuntimeException unavailable) { return reject(call, approved, attempt, "tool policy is unavailable"); }
        requireControlledCurrent(control, execution, occurrence, approved);
        if (!decision.allows()) {
            record(call, approved, attempt, "agent.tool.refused");
            return new ToolResult.Rejected(decision);
        }
        if (!record(call, approved, attempt, "agent.tool.started"))
            return reject(call, approved, attempt, "tool start audit is unavailable");
        requireControlledCurrent(control, execution, occurrence, approved);
        if (expired(started, registration.timeout)) return reject(call, approved, attempt, "tool deadline elapsed");
        try { budget.admit(approved); }
        catch (RuntimeException unavailable) { return reject(call, approved, attempt, "tool budget exhausted or unavailable"); }
        requireControlledCurrent(control, execution, occurrence, approved);
        if (expired(started, registration.timeout)) return reject(call, approved, attempt, "tool deadline elapsed");
        final String answer;
        try { answer = telemetry.call(() -> {
            try { requireControlledCurrent(control, execution, occurrence, approved); }
            catch (RuntimeException refusal) { throw new BeforeToolRefusal(refusal); }
            if (expired(started, registration.timeout)) throw new BeforeToolDeadline();
            return registration.tool.invoke(call.argumentsJson());
        }); }
        catch (BeforeToolRefusal refusal) { throw refusal.original; }
        catch (BeforeToolDeadline deadline) { return reject(call, approved, attempt, "tool deadline elapsed"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelCallRefused("controlled tool was interrupted");
        } catch (Exception failure) {
            requireControlledCurrent(control, execution, occurrence, approved);
            return failed(call, approved, attempt, registration);
        }
        requireControlledCurrent(control, execution, occurrence, approved);
        if (expired(started, registration.timeout)) return failed(call, approved, attempt, registration);
        JsonNode output = parse(answer);
        if (output == null || !valid(registration.output, output)) return failed(call, approved, attempt, registration);
        requireControlledCurrent(control, execution, occurrence, approved);
        if (!record(call, approved, attempt, "agent.tool.completed")) return failed(call, approved, attempt, registration);
        requireControlledCurrent(control, execution, occurrence, approved);
        if (expired(started, registration.timeout)) return failed(call, approved, attempt, registration);
        return new ToolResult.Completed(answer);
    }

    private static boolean expired(long started, Duration timeout) {
        long elapsed = System.nanoTime() - started;
        return elapsed < 0 || elapsed >= timeout.toNanos();
    }

    private void requireControlledCurrent(Running control, ResourceExecutionEntry execution,
                                          ResourceToolCall occurrence, Approval approved) {
        control.requireCurrent(execution, occurrence.executor());
        if (!same(approved) || !budgetCurrent(approved) || !same(approved))
            throw new ModelCallRefused("tool approval or profile changed");
        control.requireCurrent(execution, occurrence.executor());
    }

    /**
     * Synchronous effect path; returns the retained ledger outcome, not a validated tool output. Trusted factories
     * must bind locally without I/O and targets must enforce transport deadlines and their identity. The timeout
     * is an approval ceiling, not a timer around this synchronous call. This is opt-in, not production wiring.
     */
    public Effect invokeEffect(Running control, ResourceExecutionEntry execution, AllocatedResourceTask allocation,
                               ResourceToolCall occurrence, ToolCall call) {
        Objects.requireNonNull(control); Objects.requireNonNull(execution); Objects.requireNonNull(allocation);
        Objects.requireNonNull(occurrence); Objects.requireNonNull(call);
        if (controlledEffects == null) throw new ModelCallRefused("controlled effects are unavailable");
        control.requireCurrent(execution, occurrence.executor());
        Approval approved = current();
        if (approved == null || !approved.task().id().equals(call.taskId()) || approved.budgetRemainingMicros() == 0)
            throw new ModelCallRefused("no matching tool approval");
        try { profile.requireWithin(approved.task(), approved.agent()); }
        catch (IllegalArgumentException mismatch) { throw new ModelCallRefused("tool profile differs from task"); }
        if (!approved.task().permitsTool(call.toolName()) || !profile.tools().contains(call.toolName()))
            throw new ModelCallRefused("tool is not permitted");
        Registration registration = registry.get(call.toolName());
        if (registration == null || registration.effectFactory == null || registration.timeout.compareTo(profile.maxCallTime()) > 0)
            throw new ModelCallRefused("controlled tool is not registered");
        JsonNode arguments = parse(call.argumentsJson());
        if (arguments == null || !arguments.isObject() || !valid(registration.input, arguments))
            throw new ModelCallRefused("tool arguments are invalid");
        var expected = ResourceToolCall.of(allocation, profile, execution, occurrence.executor(), occurrence.nodeOccurrence(), call);
        if (!expected.equals(occurrence) || !approved.task().equals(allocation.allocation().contract()))
            throw new ModelCallRefused("tool occurrence does not match the request");
        if (!same(approved) || !budgetCurrent(approved) || !same(approved))
            throw new ModelCallRefused("tool approval or profile is unavailable");
        control.requireCurrent(execution, occurrence.executor());
        EffectTarget target = Objects.requireNonNull(registration.effectFactory.bind(call.argumentsJson()));
        control.requireCurrent(execution, occurrence.executor());
        if (!same(approved) || !budgetCurrent(approved) || !same(approved))
            throw new ModelCallRefused("tool approval or profile changed");
        String attempt = UlidGenerator.shared().next().value();
        if (!record(call, approved, attempt, "agent.tool.started"))
            throw new ModelCallRefused("tool start audit is unavailable");
        control.requireCurrent(execution, occurrence.executor());
        if (!same(approved) || !budgetCurrent(approved) || !same(approved))
            throw new ModelCallRefused("tool approval or profile changed");
        final Effect result;
        try {
            result = telemetry.callUnchecked(() -> {
                requireControlledCurrent(control, execution, occurrence, approved);
                return controlledEffects.execute(control, execution, allocation, profile, occurrence, call,
                        budget, registration.effectTarget, target);
            });
        } catch (RuntimeException failure) {
            record(call, approved, attempt, "agent.tool.failed");
            throw failure;
        }
        control.requireCurrent(execution, occurrence.executor());
        if (!same(approved) || !budgetCurrent(approved) || !same(approved)
                || !record(call, approved, attempt, "agent.tool.effect.retained"))
            throw new ModelCallRefused("tool effect outcome is withheld");
        control.requireCurrent(execution, occurrence.executor());
        if (!same(approved) || !budgetCurrent(approved) || !same(approved))
            throw new ModelCallRefused("tool effect outcome is withheld");
        return result;
    }

    private Approval current() {
        try { return approvals.get().orElse(null); }
        catch (RuntimeException unavailable) { return null; }
    }
    private boolean same(Approval expected) { return expected.equals(current()); }
    private boolean budgetCurrent(Approval expected) {
        try { budget.requireCurrent(expected); budget.requireProfile(expected, profile); return true; }
        catch (RuntimeException unavailable) { return false; }
    }
    private static final class ApprovalExpired extends Exception { }
    private static final class BeforeToolRefusal extends Exception {
        final RuntimeException original;
        BeforeToolRefusal(RuntimeException original) { this.original = original; }
    }
    private static final class BeforeToolDeadline extends Exception { }
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
