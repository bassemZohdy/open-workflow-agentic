package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.GraphInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Q5: can a thread id encode tenant, user and instance, and can two scopes collide or read each other? */
class Q05ThreadScopeTest {

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q05");
    }

    @Test
    void Q05_naiveDelimiterEncoding_collidesAndLeaksState() throws Exception {
        var ds = Db.pool("q05", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of("review"), Set.of());

        var tenantA = new ThreadScope("acme", "x|y", "9");
        var tenantB = new ThreadScope("acme|x", "y", "9");
        assertEquals(tenantA.naiveThreadId(), tenantB.naiveThreadId(), "naive ids are equal for different scopes");

        Runs.Result first = Runs.run(graph,
                GraphInput.args(Map.of(SpikeGraphs.TOPIC, "SECRET-of-tenant-A")),
                Runs.config(tenantA.naiveThreadId()));
        assertTrue(first.interrupted());

        // Tenant B reads the checkpoint that tenant A wrote, using only the naive id.
        var leaked = graph.getState(Runs.config(tenantB.naiveThreadId())).state();
        assertTrue(leaked.text(SpikeGraphs.DRAFT).contains("SECRET-of-tenant-A"),
                "naive encoding: other scope reads this checkpoint (collision confirmed)");
    }

    @Test
    void Q05_proposedEncoding_isInjectiveForTheCollisionPair_andFixedLength() {
        var tenantA = new ThreadScope("acme", "x|y", "9");
        var tenantB = new ThreadScope("acme|x", "y", "9");
        assertNotEquals(tenantA.threadId(), tenantB.threadId());
        assertEquals(tenantA.threadId().length(), new ThreadScope("a".repeat(500), "u", "i").threadId().length(),
                "length does not depend on the input size");
        assertEquals(68, new ThreadScope("a", "b", "c").threadId().length(), "ws1- prefix plus 64 hex digits");
        assertTrue(new ThreadScope("a".repeat(500), "u", "i").threadId().length() <= 255,
                "fits the VARCHAR(255) thread name column of the V1 saver");
    }

    @Test
    void Q05_proposedEncoding_samePartsGiveSameId() {
        assertEquals(new ThreadScope("t", "u", "i").threadId(), new ThreadScope("t", "u", "i").threadId());
        assertNotEquals(new ThreadScope("t", "u", "i").threadId(), new ThreadScope("t", "u", "j").threadId());
    }

    @Test
    void Q05_v1ThreadNameLongerThan255_isRejectedByTheDatabase() throws Exception {
        var ds = Db.pool("q05", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of("review"), Set.of());

        String longId = "x".repeat(300);
        Runs.Result r = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "t")), Runs.config(longId));
        assertNotNull(r.error(), "a 300-character thread id must fail in V1 (VARCHAR(255))");
        assertTrue(r.rootCause().contains("255"), "observed failure: " + r.rootCause());
    }

    @Test
    void Q05_prefixIdsDoNotLeakState() throws Exception {
        var ds = Db.pool("q05", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of("review"), Set.of());

        Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "SECRET-u1")), Runs.config("t1:u1"));
        var other = graph.stateOf(Runs.config("t1:u10"));
        assertFalse(other.isPresent(), "t1:u10 must not see t1:u1 (exact match on thread_name)");
    }

    @Test
    void Q05_anyoneWithTheThreadIdCanReadTheCheckpoint() throws Exception {
        var ds = Db.pool("q05", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of("review"), Set.of());
        var id = new ThreadScope("acme", "alice", "case-7").threadId();

        Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "alice case")), Runs.config(id));
        // The saver has no access control. It returns the state for any caller that presents the id.
        var read = graph.getState(Runs.config(id)).state();
        assertTrue(read.text(SpikeGraphs.DRAFT).contains("alice case"),
                "the id is the only capability: ownership must be checked by the runtime, not the saver");
    }
}
