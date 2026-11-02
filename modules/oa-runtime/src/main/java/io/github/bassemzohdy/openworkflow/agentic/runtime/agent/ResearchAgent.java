package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceModelCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelCall;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.Running;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Plan -> draft -> review agent as a LangGraph4j graph, embedded in the Quarkus application.
 * Checkpoints are stored in Postgres, so a run can be resumed by thread id after a restart.
 * The workflow engine (Quarkus Flow) decides when this agent runs and what happens with its result.
 */
@ApplicationScoped
public class ResearchAgent {

    @Inject
    GovernedLlmClient llm;

    @Inject
    DataSource dataSource;

    @Inject
    AgentOutputValidator outputValidator;

    /**
     * Where the approved task, and so the run's limits and acting agent, come from. With this and {@link #audit} both
     * present, every run gets a {@link NodeInterceptor} backed by its task's durable node budget.
     * An absent approval, audit sink or durable budget refuses the run before any graph node can start.
     */
    @Inject
    ApprovedModelContext approvedContext;

    @Inject
    JdbcNodeBudget nodeBudget;

    @Inject
    AgentNodeTelemetry nodeTelemetry = AgentNodeTelemetry.noop();

    /** Set by {@link #useAuditTrail}; tests may set a recording sink directly. */
    AuditSink audit;

    /**
     * Injected by the unambiguous {@code AuditTrail} type: the audit producers publish more than one bean that is an
     * {@code AuditSink}, so a field of that type would be an ambiguous dependency.
     */
    @Inject
    void useAuditTrail(io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail trail) {
        this.audit = trail;
    }

    Clock clock = Clock.systemUTC();

    /**
     * The configured checkpoint namespace. Only {@code public} is supported; any other value, or none, is refused
     * before the store is touched. It selects nothing: the saver always uses the unqualified public tables.
     */
    @ConfigProperty(name = "agentic.agent.checkpoint-schema")
    String checkpointSchema;

    private CompiledGraph<ResearchState> graph;

    private void verifyStore() {
        CheckpointProfileVerifier.requireSupportedNamespace(checkpointSchema);
        CheckpointProfileVerifier.verify(dataSource);
    }

    /**
     * Startup check, after the migrations of the application have run: a lazily created {@code @ApplicationScoped}
     * bean would otherwise not look at the store until its first request. A store that is not the pinned profile stops
     * the start; nothing is created or repaired here.
     */
    void onStart(@Observes StartupEvent event) {
        verifyStore();
    }

    /**
     * Builds the graph on the checkpoint tables that migration V75.1 owns. The saver never creates tables: the actual
     * public profile is verified (read-only, no row decoded) before the saver exists, and again before every run.
     */
    @PostConstruct
    void build() throws Exception {
        verifyStore();
        var serializer = new ObjectStreamStateSerializer<>(ResearchState::new);
        var builder = PostgresSaver.builder();
        builder.stateSerializer(serializer).datasource(dataSource).createTables(false);
        var saver = builder.build();

        this.serializer = serializer;
        this.saver = saver;
        this.graph = compile((name, action) -> action, null);
    }

    private ObjectStreamStateSerializer<ResearchState> serializer;
    private PostgresSaver saver;

    /** Compiles the plan/draft/review graph; {@code wrap} decorates each node (identity for an unwrapped run). */
    @FunctionalInterface private interface ModelExecution { String complete(String node, String system, String user); }

    private CompiledGraph<ResearchState> compile(BiFunction<String, NodeAction<ResearchState>, NodeAction<ResearchState>> wrap,
            ModelExecution model) throws Exception {
        var g = new StateGraph<>(serializer)
                .addNode("plan", node_async(wrap.apply("plan", s -> Map.of(ResearchState.PLAN,
                        outputValidator.validateText(model(model, "plan", "Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, give 3 short numbered research steps.",
                                s.text(ResearchState.TOPIC).orElse("")))))))
                .addNode("draft", node_async(wrap.apply("draft", s -> Map.of(ResearchState.DRAFT,
                        outputValidator.validateText(model(model, "draft", "Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, write a concise research brief following the plan.",
                                "Topic: " + s.text(ResearchState.TOPIC).orElse("")
                                        + "\nPlan:\n" + s.text(ResearchState.PLAN).orElse("")))))))
                .addNode("review", node_async(wrap.apply("review", s -> Map.of(ResearchState.REVIEW,
                        outputValidator.validateText(model(model, "review", "Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, reply with APPROVE or REVISE and one reason.",
                                s.text(ResearchState.DRAFT).orElse("")))))))
                .addEdge(START, "plan")
                .addEdge("plan", "draft")
                .addEdge("draft", "review")
                .addEdge("review", END);

        return g.compile(CompileConfig.builder().checkpointSaver(saver).build());
    }

    private String model(ModelExecution model, String node, String system, String user) {
        if (model == null) throw new ModelCallRefused("no approved context for this graph run");
        return model.complete(node, system, user);
    }

    /** Runs the graph for a thread (one thread per business case) and returns the final state. */
    public ResearchState run(String threadId, String topic) {
        // before any node (and so any provider call): the store may have drifted since the graph was built
        verifyStore();
        var config = RunnableConfig.builder().threadId(threadId).build();
        // Validate checkpoint compatibility before requesting an already-registered task budget.
        graph.lastStateOf(config).ifPresent(snapshot -> ResearchStateVersion.requireContinuable(snapshot.state().data()));
        var runGraph = runGraph(threadId);
        return runGraph.invoke(Map.of(ResearchState.TOPIC, topic,
                        ResearchStateVersion.KEY, ResearchStateVersion.CURRENT), config)
                .orElseThrow(() -> new IllegalStateException("Agent graph produced no state"));
    }

    /**
     * Every run requires an approved task, audit sink and previously registered durable budget. The same approval
     * used to open the budget is pinned to every model call in the graph.
     */
    private CompiledGraph<ResearchState> runGraph(String threadId) {
        if (audit == null || approvedContext == null || nodeBudget == null)
            throw new ModelCallRefused("agent run governance is unavailable");
        var approved = approvedContext.current().orElseThrow(() -> new ModelCallRefused("no approved context for this graph run"));
        // An absent checkpoint cannot prove this task never ran before an upgrade or crash.
        var budget = nodeBudget.openExisting(threadId, approved.agent(), approved.task());
        var interceptor = NodeInterceptor.forLimits(approved.agent(), threadId, approved.task().limits(), audit, clock, budget);
        var trace = io.opentelemetry.context.Context.current();
        try {
            return compile((name, action) -> GraphTraceContext.wrap(trace, interceptor.wrap(name, action, nodeTelemetry)),
                    (node, system, user) -> llm.complete(approved, system, user));
        } catch (Exception e) {
            throw new IllegalStateException("Agent graph could not be built for this run", e);
        }
    }

    /**
     * Explicit fresh-entry graph path. Each fixed graph node has a stable occurrence and uses the reserved model
     * boundary; it does not install a caller, provider, approval or route. Existing checkpoints are refused rather
     * than interpreted as permission to resume. The per-run tracker retains the caller until every admitted node
     * action physically returns, even if the graph reports failure or the caller is interrupted.
     */
    public ResearchState runGoverned(Running control, AllocatedResourceTask allocation, String topic) {
        Objects.requireNonNull(control); Objects.requireNonNull(allocation); Objects.requireNonNull(topic);
        var entry = control.entry(); var executor = control.executor();
        control.requireCurrent(entry, executor);
        var allocated = allocation.allocation();
        if (topic.isBlank() || topic.length() > ResearchRequest.MAX_TOPIC_LENGTH
                || !entry.scope().equals(allocated.scope()) || !Pep.AGENT_RESEARCH_RUN.equals(entry.scope().operation())
                || !entry.scope().requestFingerprint().equals(ResourceCallScope.requestFingerprint(ResearchRequest.canonical(topic))))
            throw new ModelCallRefused("research request differs from the retained allocation");
        if (audit == null || approvedContext == null || nodeBudget == null)
            throw new ModelCallRefused("agent run governance is unavailable");
        var approved = approvedContext.current().orElseThrow(() -> new ModelCallRefused("no approved context for this graph run"));
        control.requireCurrent(entry, executor);
        var profile = allocated.modelProfile();
        if (!approved.agent().equals(profile.agent()) || !approved.task().equals(allocated.contract())
                || !approved.modelId().equals(profile.modelId()) || approved.budgetRemainingMicros() == 0)
            throw new ModelCallRefused("model approval differs from the retained allocation");
        verifyStore();
        control.requireCurrent(entry, executor);
        var activity = new NodeActivityTracker();
        var config = RunnableConfig.builder().threadId(entry.threadId().value())
                .emitterExecutor(activity.emitterExecutor()).build();
        if (graph.lastStateOf(config).isPresent())
            throw new ModelCallRefused("existing checkpoint requires reconciliation");
        control.requireCurrent(entry, executor);
        var budget = nodeBudget.openExisting(entry.threadId().value(), approved.agent(), approved.task());
        var interceptor = NodeInterceptor.forLimits(approved.agent(), entry.threadId().value(), approved.task().limits(), audit, clock, budget);
        var trace = io.opentelemetry.context.Context.current();
        ResearchState result;
        try {
            var guarded = compile((name, action) -> activity.track(GraphTraceContext.wrap(trace,
                    interceptor.wrap(name, state -> {
                        control.requireCurrent(entry, executor);
                        var nodeOutput = action.apply(state);
                        control.requireCurrent(entry, executor);
                        return nodeOutput;
                    }, nodeTelemetry))), (node, system, user) -> {
                var call = new ModelCall(allocated.contract().id(), profile.modelId(),
                        "Instruction:\n" + system + "\n\nInput:\n" + user, profile.maxOutputTokens());
                var occurrence = ResourceModelCall.of(allocation, entry, executor, "research/" + node, call);
                return llm.completeReserved(control, allocation, occurrence, call);
            });
            control.requireCurrent(entry, executor);
            var output = guarded.stream(Map.of(ResearchState.TOPIC, topic,
                    ResearchStateVersion.KEY, ResearchStateVersion.CURRENT), config);
            boolean consumed = false;
            try (var stream = output.stream()) {
                result = stream.reduce((left, right) -> right).map(node -> node.state())
                        .orElseThrow(() -> new IllegalStateException("Agent graph produced no state"));
                consumed = true;
            } finally { if (!consumed) output.cancel(false); }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Governed agent graph could not be built or run", failure);
        } finally {
            activity.closeAndAwait();
        }
        control.requireCurrent(entry, executor);
        if (!approvedContext.current().filter(approved::equals).isPresent())
            throw new ModelCallRefused("model approval changed before graph output release");
        control.requireCurrent(entry, executor);
        return result;
    }
}
