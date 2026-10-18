package spikeb;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

/** Graph definitions used by the characterization tests and by the worker process. */
public final class SpikeGraphs {

    public static final String TOPIC = "topic";
    public static final String PLAN = "plan";
    public static final String DRAFT = "draft";
    public static final String REVIEW = "review";
    public static final String COUNT = "count";
    public static final String EVENTS = "events";
    public static final String APPROVAL = "approval";

    private SpikeGraphs() {}

    public static ObjectStreamStateSerializer<SpikeState> javaSerializer() {
        return new ObjectStreamStateSerializer<>(SpikeState::new);
    }

    /** Saver V1: the class ResearchAgent uses today. */
    public static BaseCheckpointSaver v1(StateSerializer<SpikeState> ser, DataSource ds) throws Exception {
        return PostgresSaver.builder().stateSerializer(ser).datasource(ds).createTables(true).build();
    }

    /** Saver V2: persists interruptions and keeps release tags. */
    public static BaseCheckpointSaver v2(StateSerializer<SpikeState> ser, DataSource ds) throws Exception {
        return PostgresSaverV2.builder().stateSerializer(ser).datasource(ds).createTables(true).build();
    }

    public static BaseCheckpointSaver saver(String kind, StateSerializer<SpikeState> ser, DataSource ds)
            throws Exception {
        return "v2".equals(kind) ? v2(ser, ds) : v1(ser, ds);
    }

    /** plan -> draft -> review -> END. The draft node sleeps for draftSleepMs. */
    public static CompiledGraph<SpikeState> research(
            BaseCheckpointSaver saver,
            StateSerializer<SpikeState> ser,
            Consumer<String> events,
            long draftSleepMs,
            Set<String> interruptBefore,
            Set<String> interruptAfter)
            throws Exception {
        var g = new StateGraph<>(ser)
                .addNode("plan", node_async(s -> {
                    events.accept("EXEC plan");
                    return Map.of(PLAN, "plan for " + s.text(TOPIC));
                }))
                .addNode("draft", node_async(s -> {
                    events.accept("EXEC draft");
                    sleep(draftSleepMs);
                    if (draftSleepMs > 0) {
                        events.accept("DONE draft");
                    }
                    return Map.of(DRAFT, "draft from " + s.text(PLAN));
                }))
                .addNode("review", node_async(s -> {
                    events.accept("EXEC review");
                    return Map.of(REVIEW, "reviewed: " + s.text(DRAFT));
                }))
                .addEdge(START, "plan")
                .addEdge("plan", "draft")
                .addEdge("draft", "review")
                .addEdge("review", END);
        return g.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptsBefore(interruptBefore)
                .interruptsAfter(interruptAfter)
                .build());
    }

    /** Node that interrupts (InterruptibleAction) until state carries an approval. */
    public static final class ApprovalGate
            implements org.bsc.langgraph4j.action.AsyncNodeAction<SpikeState>,
                    org.bsc.langgraph4j.action.InterruptibleAction<SpikeState> {

        private final Consumer<String> events;

        public ApprovalGate(Consumer<String> events) {
            this.events = events;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Map<String, Object>> apply(SpikeState state) {
            events.accept("EXEC gate");
            return java.util.concurrent.CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public java.util.Optional<org.bsc.langgraph4j.action.InterruptionMetadata<SpikeState>> interrupt(
                String nodeId, SpikeState state, org.bsc.langgraph4j.RunnableConfig config) {
            if (state.value(APPROVAL).isPresent()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(
                    org.bsc.langgraph4j.action.InterruptionMetadata.builder(nodeId, state)
                            .reason("approval required")
                            .build());
        }
    }

    /** plan -> gate (dynamic interrupt) -> draft -> review -> END. */
    public static CompiledGraph<SpikeState> gated(
            BaseCheckpointSaver saver, StateSerializer<SpikeState> ser, Consumer<String> events) throws Exception {
        var g = new StateGraph<>(ser)
                .addNode("plan", node_async(s -> {
                    events.accept("EXEC plan");
                    return Map.of(PLAN, "plan for " + s.text(TOPIC));
                }))
                .addNode("gate", new ApprovalGate(events))
                .addNode("draft", node_async(s -> {
                    events.accept("EXEC draft");
                    return Map.of(DRAFT, "draft after approval " + s.text(APPROVAL));
                }))
                .addNode("review", node_async(s -> {
                    events.accept("EXEC review");
                    return Map.of(REVIEW, "reviewed: " + s.text(DRAFT));
                }))
                .addEdge(START, "plan")
                .addEdge("plan", "gate")
                .addEdge("gate", "draft")
                .addEdge("draft", "review")
                .addEdge("review", END);
        return g.compile(CompileConfig.builder().checkpointSaver(saver).build());
    }

    /** START -> step (loop while count < steps) -> END. Each step appends chunk(stepIndex) to draft. */
    public static CompiledGraph<SpikeState> stepper(
            BaseCheckpointSaver saver, StateSerializer<SpikeState> ser, int steps, IntFunction<String> chunk)
            throws Exception {
        var g = new StateGraph<>(ser)
                .addNode("step", node_async(s -> {
                    int count = s.<Integer>value(COUNT).orElse(0);
                    return Map.of(DRAFT, s.text(DRAFT) + chunk.apply(count), COUNT, count + 1);
                }))
                .addEdge(START, "step")
                .addConditionalEdges("step",
                        edge_async(s -> s.<Integer>value(COUNT).orElse(0) < steps ? "more" : "done"),
                        Map.of("more", "step", "done", END));
        return g.compile(CompileConfig.builder().checkpointSaver(saver).recursionLimit(10_000).build());
    }

    /** START -> fork -> (a, b) in parallel -> join -> END. Events are collected with an appender channel. */
    public static CompiledGraph<SpikeState> parallel(
            BaseCheckpointSaver saver, StateSerializer<SpikeState> ser, Consumer<String> events, Set<String> interruptBefore)
            throws Exception {
        Map<String, Channel<?>> channels = new HashMap<>();
        channels.put(EVENTS, Channels.<String>appender(ArrayList::new));
        var g = new StateGraph<>(channels, SpikeState::new)
                .addNode("fork", node_async(s -> {
                    events.accept("EXEC fork");
                    return Map.of();
                }))
                .addNode("a", node_async(s -> {
                    events.accept("EXEC a");
                    return Map.of(EVENTS, List.of("a"));
                }))
                .addNode("b", node_async(s -> {
                    events.accept("EXEC b");
                    return Map.of(EVENTS, List.of("b"));
                }))
                .addNode("join", node_async(s -> {
                    events.accept("EXEC join");
                    return Map.of(REVIEW, "joined " + s.<List<String>>value(EVENTS).orElse(List.of()));
                }))
                .addEdge(START, "fork")
                .addEdge("fork", "a")
                .addEdge("fork", "b")
                .addEdge("a", "join")
                .addEdge("b", "join")
                .addEdge("join", END);
        return g.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptsBefore(interruptBefore)
                .build());
    }

    /** Parent: START -> sub -> END, where sub is a compiled child with c1 -> c2. */
    public static CompiledGraph<SpikeState> withSubgraph(
            BaseCheckpointSaver saver, StateSerializer<SpikeState> ser, Consumer<String> events, Set<String> childInterruptBefore)
            throws Exception {
        var child = new StateGraph<>(ser)
                .addNode("c1", node_async(s -> {
                    events.accept("EXEC c1");
                    return Map.of(PLAN, "child c1");
                }))
                .addNode("c2", node_async(s -> {
                    events.accept("EXEC c2");
                    return Map.of(DRAFT, "child c2 after " + s.text(PLAN));
                }))
                .addEdge(START, "c1")
                .addEdge("c1", "c2")
                .addEdge("c2", END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .interruptsBefore(childInterruptBefore)
                        .build());
        var parent = new StateGraph<>(ser)
                .addNode("sub", child)
                .addNode("review", node_async(s -> {
                    events.accept("EXEC review");
                    return Map.of(REVIEW, "parent review of " + s.text(DRAFT));
                }))
                .addEdge(START, "sub")
                .addEdge("sub", "review")
                .addEdge("review", END);
        return parent.compile(CompileConfig.builder().checkpointSaver(saver).build());
    }

    static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sleeping", e);
        }
    }
}
