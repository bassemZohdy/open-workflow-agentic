package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import spikeb.support.ChildProcess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Q2: a run survives kill -9 of the JVM, both while a node is in flight and while interrupted.
 * Each run uses a fresh thread id and a fresh schema. Every process is a separate JVM.
 */
class Q02RestartKill9Test {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @BeforeAll
    static void resetSchemas() throws Exception {
        Db.resetSchema("q02");
        Db.resetSchema("q02v2");
    }

    @Test
    void Q02_killDuringNode_resumeReRunsOnlyTheInFlightNode() throws Exception {
        String thread = "q02-inflight-" + System.nanoTime();

        ChildProcess first = ChildProcess.worker("slow", "v1", "q02", thread, "60000");
        try {
            first.awaitLine("EXEC draft", WAIT);
            // plan has finished and its checkpoint is in the database before we kill the JVM.
            assertTrue(Db.queryLong("select count(*) from q02.lg4jcheckpoint") >= 1,
                    "plan checkpoint must exist before the kill");
            first.kill9();
            assertFalse(first.isAlive());
        } finally {
            first.kill9();
        }

        ChildProcess second = ChildProcess.worker("resume", "v1", "q02", thread);
        List<String> out = second.finish(WAIT);

        assertEquals(List.of("EXEC draft", "EXEC review"), ChildProcess.linesWith(out, "EXEC"),
                "after resume: draft (in flight at the kill) runs again, plan does not");
        assertTrue(ChildProcess.linesWith(out, "RESULT reviewed: draft from plan for restart research")
                .size() == 1, "final result comes from the resumed run: " + out);
    }

    @Test
    void Q02_killWhileInterrupted_resumeCompletes_V1() throws Exception {
        String thread = "q02-int-v1-" + System.nanoTime();

        ChildProcess first = ChildProcess.worker("interrupt", "v1", "q02", thread);
        try {
            first.awaitLine("INTERRUPTED draft", WAIT);
            first.awaitLine("READY", WAIT);
            first.kill9();
        } finally {
            first.kill9();
        }

        List<String> out = ChildProcess.worker("resume", "v1", "q02", thread).finish(WAIT);

        assertEquals(List.of("EXEC review"), ChildProcess.linesWith(out, "EXEC"),
                "only review runs after the interrupt survives the kill");
        assertEquals(1, ChildProcess.linesWith(out, "RESULT reviewed:").size(), "output: " + out);
    }

    @Test
    void Q02_killWhileInterrupted_interruptFlagPersisted_V2() throws Exception {
        String thread = "q02-int-v2-" + System.nanoTime();

        ChildProcess first = ChildProcess.worker("interrupt", "v2", "q02v2", thread);
        try {
            first.awaitLine("INTERRUPTED draft", WAIT);
            first.awaitLine("READY", WAIT);
            // V2 records the interruption on the thread row; V1 does not.
            assertEquals(1L, Db.queryLong(
                    "select count(*) from q02v2.lg4jthread where thread_name = '" + thread + "' and is_interrupted"),
                    "V2 saver persists is_interrupted when the run stops at an interrupt");
            first.kill9();
        } finally {
            first.kill9();
        }

        List<String> out = ChildProcess.worker("resume", "v2", "q02v2", thread).finish(WAIT);

        assertEquals(List.of("EXEC review"), ChildProcess.linesWith(out, "EXEC"), "output: " + out);
        assertEquals(1, ChildProcess.linesWith(out, "RESULT reviewed:").size(), "output: " + out);
    }

    @Test
    void Q02_killWhileInterrupted_V1_hasNoInterruptFlag() throws Exception {
        String thread = "q02-int-v1flag-" + System.nanoTime();

        ChildProcess first = ChildProcess.worker("interrupt", "v1", "q02", thread);
        try {
            first.awaitLine("READY", WAIT);
            first.kill9();
        } finally {
            first.kill9();
        }
        // V1 has no is_interrupted column. Interrupt state is only the last checkpoint's nextNodeId.
        assertEquals(0L, Db.queryLong(
                "select count(*) from information_schema.columns where table_schema = 'q02' "
                        + "and table_name = 'lg4jthread' and column_name = 'is_interrupted'"),
                "V1 thread table has no interrupt column");
    }
}
