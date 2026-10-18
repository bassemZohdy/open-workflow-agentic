package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Q7: cancelling a running graph. The draft node sleeps for 3 s and emits "DONE draft" when it finishes.
 * cancel(false) only sets a flag; cancel(true) also interrupts the running thread.
 */
class Q07CancelTest {

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q07");
    }

    private record Outcome(
            List<String> nodes,
            Throwable error,
            long consumerEndedMillis,
            boolean doneDraftSeen,
            long doneDraftMillis,
            List<String> events) {}

    private static Outcome cancelDuringDraft(boolean mayInterrupt, String thread) throws Exception {
        var ds = Db.pool("q07", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var graph = SpikeGraphs.research(saver, ser, events::add, 3000, Set.of(), Set.of());
        RunnableConfig config = Runs.config(thread);

        var gen = graph.stream(GraphInput.args(Map.of(SpikeGraphs.TOPIC, "cancel")), config);
        List<String> nodes = Collections.synchronizedList(new ArrayList<>());
        Throwable[] error = new Throwable[1];
        Thread consumer = new Thread(() -> {
            try {
                for (NodeOutput<SpikeState> out : (Iterable<NodeOutput<SpikeState>>) gen.stream()::iterator) {
                    nodes.add(out.node());
                }
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        consumer.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!events.contains("EXEC draft") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(events.contains("EXEC draft"), "draft must be running before the cancel");

        long cancelledAt = System.nanoTime();
        assertTrue(gen.cancel(mayInterrupt), "cancel is accepted while the stream is running");
        consumer.join(Duration.ofSeconds(30).toMillis());
        long consumerEnded = (System.nanoTime() - cancelledAt) / 1_000_000L;

        // Watch for the running draft node to finish, then give review time to start if it would.
        long watchUntil = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!events.contains("DONE draft") && System.nanoTime() < watchUntil) {
            Thread.sleep(20);
        }
        boolean doneSeen = events.contains("DONE draft");
        long doneMillis = doneSeen ? (System.nanoTime() - cancelledAt) / 1_000_000L : -1;
        Thread.sleep(1500);
        return new Outcome(List.copyOf(nodes), error[0], consumerEnded, doneSeen, doneMillis, List.copyOf(events));
    }

    private static Runs.Result resumeFast(String thread) throws Exception {
        var ds = Db.pool("q07", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var fast = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of(), Set.of());
        return Runs.run(fast, GraphInput.resume(), Runs.config(thread));
    }

    @Test
    void Q07_cancelWithoutInterrupt_runningNodeKeepsRunning_reviewDoesNotStart() throws Exception {
        Outcome o = cancelDuringDraft(false, "q07-soft-" + System.nanoTime());
        System.out.println("Q07 soft cancel: nodes=" + o.nodes() + " error=" + o.error()
                + " consumerEndedMs=" + o.consumerEndedMillis() + " doneDraftSeen=" + o.doneDraftSeen()
                + " doneDraftMs=" + o.doneDraftMillis() + " events=" + o.events());

        assertTrue(o.consumerEndedMillis() < 1000, "the output stream ends at once after cancel (observed)");
        assertTrue(o.doneDraftSeen(), "the running draft node is not stopped by cancel(false) (observed)");
        assertFalse(o.events().contains("EXEC review"), "review does not start after cancel");
        assertFalse(o.nodes().contains("review"));
    }

    @Test
    void Q07_cancelWithInterrupt_runningNodeIsInterrupted_andThreadResumes() throws Exception {
        String thread = "q07-hard-" + System.nanoTime();
        Outcome o = cancelDuringDraft(true, thread);
        System.out.println("Q07 hard cancel: nodes=" + o.nodes() + " error=" + o.error()
                + " consumerEndedMs=" + o.consumerEndedMillis() + " doneDraftSeen=" + o.doneDraftSeen()
                + " events=" + o.events());

        assertTrue(o.consumerEndedMillis() < 1000, "the output stream ends at once after cancel(true) (observed)");
        assertFalse(o.doneDraftSeen(),
                "cancel(true) interrupts the sleeping draft: DONE never prints within 3 s (observed)");
        assertFalse(o.events().contains("EXEC review"));

        Runs.Result resumed = resumeFast(thread);
        assertNull(resumed.error(), resumed.rootCause());
        assertEquals(List.of("draft", "review"), resumed.nodes(),
                "resume re-runs the node that was cancelled mid-run (observed)");
        assertTrue(resumed.lastState().text(SpikeGraphs.REVIEW).startsWith("reviewed:"));
    }
}
