package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.GraphInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spikeb.support.ChildProcess;

/** Q10: a human edits the state of an interrupted run before it resumes (human redirect, M32). */
class Q10HumanEditTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q10");
    }

    @Test
    void Q10_editAtInterrupt_isUsedByTheResumedNode() throws Exception {
        var ds = Db.pool("q10", 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, ds);
        var graph = SpikeGraphs.research(saver, ser, e -> { }, 0, Set.of("review"), Set.of());
        var config = Runs.config("q10-edit-" + System.nanoTime());

        Runs.Result first = Runs.run(graph, GraphInput.args(Map.of(SpikeGraphs.TOPIC, "redirect")), config);
        assertTrue(first.interrupted());
        int historyBefore = graph.getStateHistory(config).size();

        graph.updateState(config, Map.of(SpikeGraphs.DRAFT, "HUMAN REDIRECT: cite the 2025 policy instead"));
        assertEquals(historyBefore + 1, graph.getStateHistory(config).size(),
                "the edit is stored as a new checkpoint, so the history keeps it (audit trail)");

        Runs.Result second = Runs.run(graph, GraphInput.resume(), config);
        assertNull(second.error(), second.rootCause());
        assertEquals("reviewed: HUMAN REDIRECT: cite the 2025 policy instead",
                second.lastState().text(SpikeGraphs.REVIEW));
        assertEquals(List.of("review"), second.nodes(), "only review runs after the edit");
    }

    @Test
    void Q10_editSurvivesKill9BeforeResume() throws Exception {
        String thread = "q10-kill-" + System.nanoTime();
        ChildProcess first = ChildProcess.worker("interrupt", "v1", "q10", thread);
        try {
            first.awaitLine("INTERRUPTED draft", WAIT);
            first.awaitLine("READY", WAIT);
            first.kill9();
        } finally {
            first.kill9();
        }

        // A second JVM applies the human edit and resumes. The edit itself is then in the database.
        List<String> out = ChildProcess.worker("edit-resume", "v1", "q10", thread).finish(WAIT);
        assertEquals(List.of("EXEC review"), ChildProcess.linesWith(out, "EXEC"), "output: " + out);
        assertEquals(List.of("RESULT reviewed: HUMAN EDIT of draft"), ChildProcess.linesWith(out, "RESULT"),
                "output: " + out);
    }
}
