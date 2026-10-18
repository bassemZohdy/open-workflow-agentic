package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.GraphInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Q1: interrupt before/after a node, dynamic interrupt, and resume in the same process. */
class Q01InterruptResumeTest {

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q01");
    }

    private static SpikeState state(Runs.Result r) {
        return r.lastState();
    }

    @Test
    void Q01_interruptBefore_resumeSameProcess() throws Exception {
        var ds = Db.pool("q01", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = new ArrayList<>();
        String thread = "q01-before-" + System.nanoTime();

        var graph = SpikeGraphs.research(saver, ser, events::add, 0, Set.of("review"), Set.of());
        var config = Runs.config(thread);

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "t1")), config);
        assertNull(first.error(), "first run must not fail: " + first.rootCause());
        assertTrue(first.interrupted(), "run must end in an interruption");
        assertEquals(List.of("plan", "draft"), first.nodes(), "nodes before the interrupt");
        assertEquals("draft", first.interruption().get().nodeId(), "interruption metadata node id (observed)");
        assertEquals(List.of("EXEC plan", "EXEC draft"), events);

        Runs.Result second = Runs.run(graph, GraphInput.resume(), config);
        assertNull(second.error(), "resume must not fail: " + second.rootCause());
        assertFalse(second.interrupted(), "resume must run to the end");
        assertEquals(List.of("review"), second.nodes(), "resume runs only the remaining node");
        assertTrue(state(second).text(SpikeGraphs.REVIEW).startsWith("reviewed: draft from plan for t1"));
        assertEquals(List.of("EXEC plan", "EXEC draft", "EXEC review"), events, "no node ran twice");
    }

    @Test
    void Q01_interruptAfter_resumeSameProcess() throws Exception {
        var ds = Db.pool("q01", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = new ArrayList<>();
        String thread = "q01-after-" + System.nanoTime();

        var graph = SpikeGraphs.research(saver, ser, events::add, 0, Set.of(), Set.of("plan"));
        var config = Runs.config(thread);

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "t2")), config);
        assertNull(first.error(), first.rootCause());
        assertTrue(first.interrupted(), "interruptAfter must stop the run");
        assertEquals("plan", first.interruption().get().nodeId(), "interruption metadata node id (observed)");
        assertEquals(List.of("plan"), first.nodes());

        Runs.Result second = Runs.run(graph, GraphInput.resume(), config);
        assertNull(second.error(), second.rootCause());
        assertEquals(List.of("draft", "review"), second.nodes());
        assertEquals(List.of("EXEC plan", "EXEC draft", "EXEC review"), events);
    }

    @Test
    void Q01_dynamicInterruptFromNode_resumeAfterApproval() throws Exception {
        var ds = Db.pool("q01", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = new ArrayList<>();
        String thread = "q01-dyn-" + System.nanoTime();

        var graph = SpikeGraphs.gated(saver, ser, events::add);
        var config = Runs.config(thread);

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "t3")), config);
        assertNull(first.error(), first.rootCause());
        assertTrue(first.interrupted(), "InterruptibleAction must stop the run");
        assertEquals("gate", first.interruption().get().nodeId());
        assertEquals("approval required", first.interruption().get().reason().orElse(""));
        assertEquals(List.of("plan"), first.nodes(), "gate interrupts before it produces output (observed)");
        assertEquals(List.of("EXEC plan"), events, "gate apply() is not called while interrupt() asks to stop (observed)");

        // Resume without approval: the gate interrupts again, nothing else runs.
        Runs.Result still = Runs.run(graph, GraphInput.resume(), config);
        assertNull(still.error(), still.rootCause());
        assertTrue(still.interrupted(), "resume without approval interrupts again (observed)");

        // Resume with approval passed as resume input: the gate sees it and passes.
        Runs.Result approved = Runs.run(graph, GraphInput.resume(Map.of(SpikeGraphs.APPROVAL, "yes")), config);
        assertNull(approved.error(), approved.rootCause());
        assertFalse(approved.interrupted());
        assertTrue(state(approved).text(SpikeGraphs.REVIEW).contains("reviewed"));
        assertTrue(events.contains("EXEC draft"));
    }

    @Test
    void Q01_stateAfterInterruptIsVisibleToCaller() throws Exception {
        var ds = Db.pool("q01", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of("review"), Set.of());
        var config = Runs.config("q01-state-" + System.nanoTime());

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "t4")), config);
        assertTrue(first.interrupted());
        assertEquals("draft from plan for t4", first.interruption().get().state().text(SpikeGraphs.DRAFT));
        assertEquals("draft from plan for t4", graph.getState(config).state().text(SpikeGraphs.DRAFT),
                "getState reads the same checkpoint as the interruption");
        assertEquals("review", graph.getState(config).nextNodeId(),
                "checkpoint persisted at the interrupt points at the node that will run on resume");
        assertEquals("declared interruption before nodeId: [review]",
                first.interruption().get().reason().orElse(""),
                "declared interrupt reason (observed)");
    }
}
