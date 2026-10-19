package io.github.bassemzohdy.openworkflow.agentic.testkit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.testkit.model.ScriptedLlmClient.Outcome;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScriptedLlmClientTest {

    @Test
    void answersInScriptOrderAndRecordsEachCall() {
        ScriptedLlmClient llm = ScriptedLlmClient.create().answer("first").answer("second");
        assertEquals("first", llm.complete("sys", "one"));
        assertEquals("second", llm.complete("sys", "two"));
        assertEquals(2, llm.callCount());
        assertEquals("two", llm.calls().get(1).user());
        assertEquals(Outcome.ANSWERED, llm.calls().get(1).outcome());
        assertEquals(0, llm.remaining());
    }

    @Test
    void emptyScriptFailsStrictlyWithoutFallback() {
        ScriptedLlmClient llm = ScriptedLlmClient.create();
        assertThrows(IllegalStateException.class, () -> llm.complete("s", "u"));
        assertEquals(Outcome.UNSCRIPTED, llm.calls().get(0).outcome());
    }

    @Test
    void fallbackAnswersOnceTheScriptIsEmpty() {
        ScriptedLlmClient llm = ScriptedLlmClient.create().answer("scripted")
                .fallback((system, user) -> "[fallback] " + user);
        assertEquals("scripted", llm.complete("s", "a"));
        assertEquals("[fallback] b", llm.complete("s", "b"));
    }

    @Test
    void injectedFailureIsThrownAndRecordedAsFailed() {
        IllegalArgumentException boom = new IllegalArgumentException("provider 500");
        ScriptedLlmClient llm = ScriptedLlmClient.create().fail(boom).answer("after");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> llm.complete("s", "u"));
        assertEquals("provider 500", thrown.getMessage());
        assertEquals(Outcome.FAILED, llm.calls().get(0).outcome());
        assertEquals("after", llm.complete("s", "u2"));
    }

    @Test
    void aFailureThatIsAnIllegalStateExceptionIsStillLabelledFailedNotUnscripted() {
        ScriptedLlmClient llm = ScriptedLlmClient.create().fail(new IllegalStateException("injected"));
        assertThrows(IllegalStateException.class, () -> llm.complete("s", "u"));
        assertEquals(Outcome.FAILED, llm.calls().get(0).outcome());
    }

    @Test
    void timeoutWaitsThenThrowsModelTimeoutException() {
        ScriptedLlmClient llm = ScriptedLlmClient.create().timeout(Duration.ofMillis(150));
        long start = System.nanoTime();
        assertThrows(ModelTimeoutException.class, () -> llm.complete("s", "u"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 140);
        assertEquals(Outcome.TIMED_OUT, llm.calls().get(0).outcome());
    }

    @Test
    void delayedAnswerIsSlowButCorrect() {
        ScriptedLlmClient llm = ScriptedLlmClient.create().delayedAnswer(Duration.ofMillis(100), "late");
        long start = System.nanoTime();
        assertEquals("late", llm.complete("s", "u"));
        assertTrue(llm.calls().get(0).elapsed().toMillis() >= 90);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 90);
    }

    @Test
    void tokenCountsAreReportedExplicitlyOrEstimated() {
        ScriptedLlmClient llm = ScriptedLlmClient.create()
                .answer("exact", 100, 7)
                .answer("abcdefgh");
        llm.complete("system!", "user");
        llm.complete("system!", "user");
        assertEquals(100, llm.calls().get(0).inputTokens());
        assertEquals(7, llm.calls().get(0).outputTokens());
        // "system!" (7 chars) and "user" (4 chars) -> 2 + 1 tokens; "abcdefgh" (8 chars) -> 2 tokens
        assertEquals(3, llm.calls().get(1).inputTokens());
        assertEquals(2, llm.calls().get(1).outputTokens());
        ScriptedLlmClient.Usage usage = llm.usage();
        assertEquals(2, usage.calls());
        assertEquals(103, usage.inputTokens());
        assertEquals(9, usage.outputTokens());
    }

    @Test
    void callsThatProduceNoAnswerCountNoTokens() {
        ScriptedLlmClient llm = ScriptedLlmClient.create().fail(new RuntimeException("x"));
        assertThrows(RuntimeException.class, () -> llm.complete("long system prompt", "user"));
        assertEquals(0, llm.calls().get(0).inputTokens());
        assertEquals(0, llm.usage().inputTokens());
    }

    @Test
    void recordedCallsCarryThePromptsSentToTheModel() {
        ScriptedLlmClient llm = ScriptedLlmClient.create().answer("ok");
        llm.complete("You plan research.", "Topic: agents");
        assertEquals("You plan research.", llm.calls().get(0).system());
        assertEquals("Topic: agents", llm.calls().get(0).user());
        assertEquals(1, llm.calls().get(0).index());
    }
}
