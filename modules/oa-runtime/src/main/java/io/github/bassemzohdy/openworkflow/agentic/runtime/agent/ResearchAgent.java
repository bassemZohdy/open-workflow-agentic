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
import java.util.function.BiFunction;
import javax.sql.DataSource;
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
     * present, every run gets its own {@link NodeInterceptor} (step and wall-clock limits, started/completed records).
     * Left unset, the graph runs unwrapped exactly as before. With the default context (none) a run is refused at its
     * first model call regardless.
     */
    @Inject
    ApprovedModelContext approvedContext;

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
        this.graph = compile((name, action) -> action);
    }

    private ObjectStreamStateSerializer<ResearchState> serializer;
    private PostgresSaver saver;

    /** Compiles the plan/draft/review graph; {@code wrap} decorates each node (identity for an unwrapped run). */
    private CompiledGraph<ResearchState> compile(BiFunction<String, NodeAction<ResearchState>, NodeAction<ResearchState>> wrap) throws Exception {
        var g = new StateGraph<>(serializer)
                .addNode("plan", node_async(wrap.apply("plan", s -> Map.of(ResearchState.PLAN,
                        outputValidator.validateText(llm.complete("Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, give 3 short numbered research steps.",
                                s.text(ResearchState.TOPIC).orElse("")))))))
                .addNode("draft", node_async(wrap.apply("draft", s -> Map.of(ResearchState.DRAFT,
                        outputValidator.validateText(llm.complete("Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, write a concise research brief following the plan.",
                                "Topic: " + s.text(ResearchState.TOPIC).orElse("")
                                        + "\nPlan:\n" + s.text(ResearchState.PLAN).orElse("")))))))
                .addNode("review", node_async(wrap.apply("review", s -> Map.of(ResearchState.REVIEW,
                        outputValidator.validateText(llm.complete("Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, reply with APPROVE or REVISE and one reason.",
                                s.text(ResearchState.DRAFT).orElse("")))))))
                .addEdge(START, "plan")
                .addEdge("plan", "draft")
                .addEdge("draft", "review")
                .addEdge("review", END);

        return g.compile(CompileConfig.builder().checkpointSaver(saver).build());
    }

    /** Runs the graph for a thread (one thread per business case) and returns the final state. */
    public ResearchState run(String threadId, String topic) {
        // before any node (and so any provider call): the store may have drifted since the graph was built
        verifyStore();
        var config = RunnableConfig.builder().threadId(threadId).build();
        return runGraph(threadId).invoke(Map.of(ResearchState.TOPIC, topic), config)
                .orElseThrow(() -> new IllegalStateException("Agent graph produced no state"));
    }

    /**
     * The graph for one run. With an approved context and an audit sink, a fresh {@link NodeInterceptor} (never shared
     * between runs, since its step counter and deadline belong to one) wraps every node using the approved task's
     * limits and the approved agent. Without an approved context the shared unwrapped graph is used and the first
     * governed model call refuses, as before.
     */
    private CompiledGraph<ResearchState> runGraph(String threadId) {
        if (audit == null || approvedContext == null) return graph;
        var approved = approvedContext.current().orElse(null);
        if (approved == null) return graph;
        var interceptor = NodeInterceptor.forLimits(approved.agent(), threadId, approved.task().limits(), audit, clock);
        try {
            return compile(interceptor::wrap);
        } catch (Exception e) {
            throw new IllegalStateException("Agent graph could not be built for this run", e);
        }
    }
}
