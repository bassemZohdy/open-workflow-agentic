package io.github.bassemzohdy.openworkflow.spikec;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LlmClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.MDC;

/**
 * The agent of this spike: a two-node LangGraph4j graph with the V2 Postgres saver.
 *
 * <ul>
 *   <li>{@code draft} calls the model (scripted double) and records a step.</li>
 *   <li>{@code publish} is the external effect. It records a step, writes the simulated external system with the
 *       idempotency key {@code <thread>:publish}, then (for the kill -9 tests) waits before it returns.</li>
 *   <li>The graph interrupts before {@code publish}: that interrupt is the human approval gate.</li>
 * </ul>
 * The thread is the derived {@link ThreadId}. The context is stored in the state as JSON.
 */
@ApplicationScoped
public class ResearchGraph {

    static final String STATUS_AWAITING = "AWAITING_APPROVAL";
    static final String STATUS_COMPLETED = "COMPLETED";

    @Inject
    LlmClient llm;

    @Inject
    Store store;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "spikec.draft.delay-ms", defaultValue = "0")
    long draftDelayMs;

    @ConfigProperty(name = "spikec.publish.delay-ms", defaultValue = "0")
    long publishDelayMs;

    /** When false the effect gets a random key, which is the naive form the spike uses as a control. */
    @ConfigProperty(name = "spikec.effect.keyed", defaultValue = "true")
    boolean effectKeyed;

    /**
     * Test seams, package-private and never set in production: wrap the checkpoint saver, replace the serializer,
     * and throw from a node by name before it does its work. The defaults change nothing.
     */
    UnaryOperator<BaseCheckpointSaver> saverDecorator = saver -> saver;
    JsonSpikeSerializer serializer = new JsonSpikeSerializer();
    Consumer<String> nodeFault = node -> {
    };

    private CompiledGraph<SpikeState> graph;
    private BaseCheckpointSaver checkpoints;

    /** Result of driving the graph: the status it stopped in, and the thread. */
    public record Result(String status, String threadId) {
    }

    @PostConstruct
    void build() throws Exception {
        var ser = serializer;
        BaseCheckpointSaver saver = saverDecorator.apply(
                PostgresSaverV2.builder().stateSerializer(ser).datasource(dataSource).createTables(true).build());
        var g = new StateGraph<>(ser)
                .addNode("draft", node_async(s -> {
                    nodeFault.accept("draft");
                    Ctx ctx = Ctx.fromJson(s.text(SpikeState.CTX));
                    store.recordStep(ctx.threadId().value(), "draft", ctx, MDC.get("correlationId"));
                    sleep(draftDelayMs);
                    return Map.of(SpikeState.DRAFT, llm.complete("You write a concise research brief.",
                            "Topic: " + ctx.topic()));
                }))
                .addNode("publish", node_async(s -> {
                    nodeFault.accept("publish");
                    Ctx ctx = Ctx.fromJson(s.text(SpikeState.CTX));
                    String thread = ctx.threadId().value();
                    store.recordStep(thread, "publish", ctx, MDC.get("correlationId"));
                    String key = effectKeyed ? thread + ":publish" : UUID.randomUUID().toString();
                    store.recordEffect(key, thread, "publish", s.text(SpikeState.DRAFT));
                    sleep(publishDelayMs);
                    return Map.of(SpikeState.PUBLISHED, "yes");
                }))
                .addEdge(START, "draft")
                .addEdge("draft", "publish")
                .addEdge("publish", END);
        this.checkpoints = saver;
        this.graph = g.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptsBefore(Set.of("publish"))
                .build());
    }

    /**
     * Starts the run for a context, or continues it when the run record already exists (an earlier attempt was
     * interrupted or killed). A continuation starts again from the input only when the checkpoint is positively
     * missing (see {@link #checkpointExists}). Every other failure propagates: a fresh start would replay the
     * nodes that already ran, which repeats the model call and, after an approval, the effect.
     */
    public Result run(Ctx ctx, String bridge, String headerCorrelation) {
        String thread = ctx.threadId().value();
        boolean existed = store.run(thread) != null;
        if (!existed) {
            store.noteReceived(bridge, ctx, headerCorrelation, "RUNNING");
        }
        Result result;
        if (existed && checkpointExists(thread)) {
            result = drive(GraphInput.resume(), ctx);
        } else {
            result = drive(GraphInput.args(Map.of(SpikeState.CTX, ctx.toJson())), ctx);
        }
        store.setRunStatus(thread, result.status());
        return result;
    }

    /**
     * True when the saver has a checkpoint for the thread. The saver answers {@code Optional.empty()} only when
     * there is none; a store, serializer or other error is thrown, and the caller must not treat it as absence.
     */
    private boolean checkpointExists(String thread) {
        return checkpoints.get(RunnableConfig.builder().threadId(thread).build()).isPresent();
    }

    /** Resumes an interrupted run after the approval, using the context stored with the run. */
    public Result resume(Ctx ctx) {
        Result result = drive(GraphInput.resume(), ctx);
        store.setRunStatus(ctx.threadId().value(), result.status());
        return result;
    }

    private Result drive(GraphInput input, Ctx ctx) {
        RunnableConfig config = RunnableConfig.builder().threadId(ctx.threadId().value()).build();
        var gen = graph.stream(input, config);
        for (NodeOutput<SpikeState> ignored : (Iterable<NodeOutput<SpikeState>>) gen.stream()::iterator) {
            // the stream is consumed for its side effects; the result is read below
        }
        Object done = AsyncGenerator.resultValue(gen).orElse(null);
        boolean interrupted = done instanceof InterruptionMetadata<?>
                || (done instanceof GraphResult gr && gr.type() == GraphResult.Type.INTERRUPTION_METADATA);
        return new Result(interrupted ? STATUS_AWAITING : STATUS_COMPLETED, ctx.threadId().value());
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during a graph node", e);
        }
    }
}
