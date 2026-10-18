package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.GraphInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Q8: parallel branches and subgraphs with checkpoints. */
class Q08ParallelSubgraphTest {

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q08");
    }

    @Test
    void Q08_parallelBranches_eachRunsOnce_joinRunsOnce() throws Exception {
        var ds = Db.pool("q08", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var graph = SpikeGraphs.parallel(saver, ser, events::add, Set.of());

        Runs.Result r = Runs.run(graph, GraphInput.args(Map.of()), Runs.config("q08-par-" + System.nanoTime()));
        assertNull(r.error(), r.rootCause());
        assertEquals(1, Collections.frequency(r.nodes(), "join"), "join runs once: " + r.nodes());
        assertEquals(1, Collections.frequency(events, "EXEC a"));
        assertEquals(1, Collections.frequency(events, "EXEC b"));
        assertTrue(r.lastState().text(SpikeGraphs.REVIEW).contains("a") && r.lastState().text(SpikeGraphs.REVIEW).contains("b"),
                "appender channel merges both branch outputs: " + r.lastState().text(SpikeGraphs.REVIEW));
    }

    @Test
    void Q08_parallelBranches_interruptBeforeJoin_resumeRunsJoinOnce() throws Exception {
        var ds = Db.pool("q08", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var graph = SpikeGraphs.parallel(saver, ser, events::add, Set.of("join"));
        var config = Runs.config("q08-parint-" + System.nanoTime());

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of()), config);
        assertNull(first.error(), first.rootCause());
        assertTrue(first.interrupted(), "interrupt before join");
        assertEquals(1, Collections.frequency(events, "EXEC a"));
        assertEquals(1, Collections.frequency(events, "EXEC b"));
        assertEquals(0, Collections.frequency(events, "EXEC join"));

        Runs.Result second = Runs.run(graph, GraphInput.resume(), config);
        assertNull(second.error(), second.rootCause());
        assertFalse(second.interrupted());
        assertEquals(1, Collections.frequency(events, "EXEC join"), "join runs once after resume");
        assertEquals(1, Collections.frequency(events, "EXEC a"), "branch a is not re-run");
        assertEquals(1, Collections.frequency(events, "EXEC b"), "branch b is not re-run");
    }

    @Test
    void Q08_subgraphInterruptInsideChild_propagatesToParent() throws Exception {
        var ds = Db.pool("q08", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var graph = SpikeGraphs.withSubgraph(saver, ser, events::add, Set.of("c2"));
        var config = Runs.config("q08-subp-" + System.nanoTime());

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of()), config);
        assertNull(first.error(), first.rootCause());
        assertTrue(first.interrupted(), "interrupt inside the child is reported by the parent (observed: GraphResult)");
        assertEquals(List.of("EXEC c1"), events, "c1 ran, c2 is interrupted");
        System.out.println("Q08 subgraph interruption nodeId=" + first.interruption().get().nodeId()
                + " reason=" + first.interruption().get().reason().orElse(""));
    }

    /**
     * Observed on V1: after the child is interrupted, the subgraph thread row is already released
     * (is_released = true) while the parent thread is still open. When the child resumes and completes, the
     * saver tries to release the subgraph thread and fails with "active Thread ... not found".
     */
    @Disabled("Observed failure, V1 saver: resume after a child interrupt fails with "
            + "IllegalStateException: active Thread '<thread>_subgraph_sub' not found (see Q08 notes)")
    @Test
    void Q08_subgraphInterruptInsideChild_resumeParentCompletes_V1() throws Exception {
        var ds = Db.pool("q08", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var graph = SpikeGraphs.withSubgraph(saver, ser, events::add, Set.of("c2"));
        var config = Runs.config("q08-subv1-" + System.nanoTime());

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of()), config);
        assertTrue(first.interrupted());
        Runs.Result second = Runs.run(graph, GraphInput.resume(), config);
        assertNull(second.error(), second.rootCause());
        assertEquals("parent review of child c2 after child c1", second.lastState().text(SpikeGraphs.REVIEW));
    }

    /** Same path on V2 (persists interruptions, keeps release tags). Observed to fail as well. */
    @Disabled("Observed failure, V2 saver: resume after a child interrupt fails with SQLException: "
            + "No LG4JThread found for thread_id '<thread>_subgraph_sub'")
    @Test
    void Q08_subgraphInterruptInsideChild_resumeParent_V2_probe() throws Exception {
        Db.resetSchema("q08v2");
        var ds = Db.pool("q08v2", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v2(ser, ds);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var graph = SpikeGraphs.withSubgraph(saver, ser, events::add, Set.of("c2"));
        var config = Runs.config("q08-subv2-" + System.nanoTime());

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of()), config);
        assertTrue(first.interrupted(), "V2 first run interrupts (observed)");
        Runs.Result second = Runs.run(graph, GraphInput.resume(), config);
        System.out.println("Q08 V2 subgraph resume: nodes=" + second.nodes() + " error=" + second.rootCause()
                + " events=" + events);
        assertNull(second.error(), second.rootCause());
    }
}
