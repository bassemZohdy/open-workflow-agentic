package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.SnapshotOutput;
import org.bsc.async.AsyncGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Q6: streaming node outputs from a run. */
class Q06StreamingTest {

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q06");
    }

    @Test
    void Q06_valuesStreamEmitsEachNodeWhenItCompletes() throws Exception {
        var ds = Db.pool("q06", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        CompiledGraph<SpikeState> graph = SpikeGraphs.research(saver, ser, e -> { }, 400, Set.of(), Set.of());
        RunnableConfig config = Runs.config("q06-values-" + System.nanoTime());

        List<String> raw = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        var gen = graph.stream(GraphInput.args(Map.of(SpikeGraphs.TOPIC, "stream")), config);
        long start = System.nanoTime();
        var it = gen.stream().iterator();
        while (it.hasNext()) {
            NodeOutput<SpikeState> out = it.next();
            raw.add(out.node());
            times.add(System.nanoTime() - start);
        }

        assertEquals(List.of("__START__", "plan", "draft", "review", "__END__"), raw,
                "every node output is emitted, including START and END markers (observed)");
        long planAt = times.get(raw.indexOf("plan"));
        long reviewAt = times.get(raw.indexOf("review"));
        assertTrue(reviewAt - planAt >= 350_000_000L,
                "plan is emitted before draft finishes (400 ms sleep): streamed, not batched");
    }

    @Test
    void Q06_snapshotsStreamCarriesCheckpointIds() throws Exception {
        var ds = Db.pool("q06", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        CompiledGraph<SpikeState> graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of(), Set.of());
        String thread = "q06-snap-" + System.nanoTime();
        RunnableConfig config = RunnableConfig.builder()
                .threadId(thread)
                .streamMode(CompiledGraph.StreamMode.SNAPSHOTS)
                .build();

        List<NodeOutput<SpikeState>> outs = graph.stream(
                GraphInput.args(Map.of(SpikeGraphs.TOPIC, "snap")), config).stream().toList();

        List<SnapshotOutput> snaps = outs.stream()
                .filter(o -> o instanceof SnapshotOutput)
                .map(o -> (SnapshotOutput) o)
                .toList();
        assertTrue(snaps.size() >= 3, "snapshot per checkpoint: " + snaps.size());
        assertNotNull(snaps.get(0).checkpointId());
        assertEquals(snaps.size(), snaps.stream().map(SnapshotOutput::checkpointId).distinct().count(),
                "checkpoint ids are distinct");
        // The run completed, so the thread was released. list() only returns unreleased threads (observed).
        assertEquals(0, graph.getStateHistory(config).size(), "history API is empty after completion (observed)");
        assertEquals(snaps.size(), Db.queryLong("select count(*) from q06.lg4jcheckpoint c join q06.lg4jthread t "
                + "on c.thread_id = t.thread_id where t.thread_name = '" + thread + "'"),
                "the checkpoint rows are still in the table after completion (no cleanup)");
    }
}
