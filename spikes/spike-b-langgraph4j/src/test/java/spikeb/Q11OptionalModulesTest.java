package spikeb;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.agui.sdk.AGUIHook;
import org.junit.jupiter.api.BeforeAll;
import org.bsc.langgraph4j.otel.OTELWrapCallTraceHook;
import org.junit.jupiter.api.Test;

/**
 * Q11: langgraph4j-opentelemetry and langgraph4j-ag-ui-sdk at 1.9.5 (test scope in this spike).
 * Smoke tests only: the modules resolve, their hooks can be added to a checkpointing graph, and a run works.
 */
class Q11OptionalModulesTest {

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q11");
    }

    @Test
    void Q11_openTelemetryHook_runsWithCheckpointingGraph() throws Exception {
        var ds = Db.pool("q11", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var otelHook = new OTELWrapCallTraceHook<SpikeState>(ser);
        var graph = new StateGraph<>(ser)
                .addWrapCallNodeHook(otelHook)
                .addNode("plan", node_async(s -> Map.of(SpikeGraphs.PLAN, "plan for " + s.text(SpikeGraphs.TOPIC))))
                .addNode("review", node_async(s -> Map.of(SpikeGraphs.REVIEW, "reviewed " + s.text(SpikeGraphs.PLAN))))
                .addEdge(START, "plan")
                .addEdge("plan", "review")
                .addEdge("review", END)
                .compile(CompileConfig.builder().checkpointSaver(saver).interruptsBefore(Set.of("review")).build());
        var config = Runs.config("q11-otel-" + System.nanoTime());

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "otel")), config);
        assertNull(first.error(), first.rootCause());
        assertTrue(first.interrupted());
        Runs.Result second = Runs.run(graph, GraphInput.resume(), config);
        assertNull(second.error(), second.rootCause());
        assertEquals("reviewed plan for otel", second.lastState().text(SpikeGraphs.REVIEW));
    }

    @Test
    void Q11_agUiStepHook_emitsStepEventsInPlainStream() throws Exception {
        // RunnableConfig.customDispatcher() throws unless a dispatcher is set. CompiledGraph sets one for every
        // stream (updateRunnableConfigMetadata uses putIfAbsent), so the hook works without extra setup.
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        var graph = new StateGraph<>(SpikeGraphs.javaSerializer())
                .addWrapCallNodeHook(AGUIHook.<SpikeState>stepEvents())
                .addNode("plan", node_async(s -> {
                    seen.add("plan body");
                    return Map.of(SpikeGraphs.PLAN, "p");
                }))
                .addEdge(START, "plan")
                .addEdge("plan", END)
                .compile();

        List<String> kinds = new ArrayList<>();
        var it = graph.stream(GraphInput.args(Map.of(SpikeGraphs.TOPIC, "agui")),
                Runs.config("q11-agui-" + System.nanoTime())).stream().iterator();
        while (it.hasNext()) {
            var out = it.next();
            kinds.add(out.getClass().getSimpleName() + ":" + out.node());
        }
        System.out.println("Q11 ag-ui plain stream outputs: " + kinds + " body=" + seen);
        assertEquals(List.of("plan body"), seen, "the node body runs once");
        long stepEvents = kinds.stream().filter(k -> k.startsWith("AGUINodeOutput:")).count();
        assertTrue(stepEvents >= 2, "step started and finished events are streamed (observed count: " + stepEvents + ")");
    }
}
