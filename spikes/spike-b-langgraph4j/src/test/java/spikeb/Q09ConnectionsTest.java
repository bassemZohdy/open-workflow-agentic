package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Q9: how many connections the saver uses, and whether a graph can run on a pool of one connection. */
class Q09ConnectionsTest {

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q09");
    }

    @Test
    void Q09_sequentialRun_neverHoldsMoreThanOneConnection() throws Exception {
        var pool = Db.pool("q09", 4);
        var stats = new Db.DbStats();
        DataSource ds = Db.counting(pool, stats);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var graph = SpikeGraphs.stepper(saver, ser, 20, i -> "x".repeat(50));

        stats.reset();
        Runs.Result r = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.DRAFT, "")),
                Runs.config("q09-seq-" + System.nanoTime()));
        assertNull(r.error(), r.rootCause());
        System.out.println("Q09 sequential 20 steps: " + stats);
        assertEquals(1, stats.peakOpen.get(), "the saver returns each connection before the next operation");
        assertTrue(stats.connectionsOpened.get() >= 20 * 2,
                "two connections per checkpoint write (load, then insert) observed: " + stats.connectionsOpened.get());
        assertEquals(0, stats.openNow.get(), "no connection is left open after the run");
        pool.close();
    }

    @Test
    void Q09_parallelBranches_completeOnPoolOfOne() throws Exception {
        var pool = Db.pool("q09", 1);
        var stats = new Db.DbStats();
        DataSource ds = Db.counting(pool, stats);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var graph = SpikeGraphs.parallel(saver, ser, events::add, Set.of());

        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<Runs.Result> f = ex.submit((Callable<Runs.Result>) () ->
                    Runs.run(graph, GraphInput.args(Map.of()), Runs.config("q09-pool1-" + System.nanoTime())));
            Runs.Result r = f.get(60, TimeUnit.SECONDS);
            assertNull(r.error(), r.rootCause());
            assertEquals(1, Collections.frequency(r.nodes(), "join"));
            assertEquals(1, stats.peakOpen.get(), "parallel branches share one connection at a time");
        } finally {
            ex.shutdownNow();
            pool.close();
        }
    }

    @Test
    void Q09_builderWithoutDataSource_runsEndToEnd() throws Exception {
        // Without a DataSource the saver builds its own PGSimpleDataSource (read in source:
        // AbstractPostgresSaver.AbstractBuilder.validate). That source is not pooled. This test runs a graph
        // through that path; the connection behaviour of that path is read in source, not measured here.
        try (var c = Db.admin(); var st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public.lg4jcheckpoint, public.lg4jthread CASCADE");
        }
        var ser = SpikeGraphs.javaSerializer();
        var saver = PostgresSaver.builder()
                .stateSerializer(ser)
                .host("localhost")
                .port(5432)
                .user(Db.USER)
                .password(Db.PASSWORD)
                .database("spike_b")
                .createTables(true)
                .build();
        var graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of("review"), Set.of());
        String thread = "q09-own-" + System.nanoTime();
        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "own")), Runs.config(thread));
        assertNull(first.error(), first.rootCause());
        assertTrue(first.interrupted());
        Runs.Result second = Runs.run(graph, GraphInput.resume(), Runs.config(thread));
        assertNull(second.error(), second.rootCause());
        assertTrue(second.lastState().text(SpikeGraphs.REVIEW).startsWith("reviewed:"));
    }
}
