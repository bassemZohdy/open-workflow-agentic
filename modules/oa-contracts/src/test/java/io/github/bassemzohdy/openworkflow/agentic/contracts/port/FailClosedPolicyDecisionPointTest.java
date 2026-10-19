package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.doubles.ScriptedPolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FailClosedPolicyDecisionPointTest {

    private static final AuthorizationRequest REQUEST = new AuthorizationRequest(Samples.USER,
            ActionKind.TOOL_CALL, "tool:case-search", Samples.TASK_ID);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static Decision allow() {
        return new Decision("engine", DecisionOutcome.ALLOW, "permit matched", new PolicyRef("p", "1"),
                Samples.NOW);
    }

    private static FailClosedPolicyDecisionPoint guard(PolicyDecisionPoint delegate) {
        return new FailClosedPolicyDecisionPoint(delegate, TIMEOUT);
    }

    @Test
    void anAllowFromTheDelegatePassesThroughUnchanged() {
        Decision allow = allow();
        assertSame(allow, guard(r -> allow).decide(REQUEST));
    }

    @Test
    void aDenyFromTheDelegatePassesThrough() {
        Decision deny = Samples.decision();
        Decision result = guard(r -> deny).decide(REQUEST);
        assertSame(deny, result);
        assertEquals(DecisionOutcome.DENY, result.outcome());
    }

    @Test
    void aThrowingDelegateGivesAnErrorDecisionThatIsNotAnAllow() {
        PolicyDecisionPoint broken = r -> {
            throw new IllegalStateException("secret-policy-body leaked in the message");
        };
        Decision result = guard(broken).decide(REQUEST);
        assertEquals(DecisionOutcome.ERROR, result.outcome());
        assertFalse(result.allows());
        assertEquals(PolicyRef.FAIL_CLOSED, result.policy());
        assertEquals(FailClosedPolicyDecisionPoint.DECIDED_BY, result.decidedBy());
        assertTrue(result.reason().contains("IllegalStateException"));
        assertFalse(result.reason().contains("secret-policy-body"), "the exception message is not copied");
    }

    @Test
    void anErrorThrownAsAnErrorIsAlsoRefused() {
        PolicyDecisionPoint broken = r -> {
            throw new AssertionError("engine invariant");
        };
        assertEquals(DecisionOutcome.ERROR, guard(broken).decide(REQUEST).outcome());
    }

    @Test
    void aNullAnswerIsNeverAnAllow() {
        Decision result = guard(r -> null).decide(REQUEST);
        assertEquals(DecisionOutcome.ERROR, result.outcome());
        assertFalse(result.allows());
    }

    @Test
    void aSlowDelegateTimesOutAsAnError() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        PolicyDecisionPoint slow = r -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return allow();
        };
        long started = System.nanoTime();
        try {
            Decision result = new FailClosedPolicyDecisionPoint(slow, Duration.ofMillis(100)).decide(REQUEST);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(DecisionOutcome.ERROR, result.outcome(), "a late allow must not be used");
            assertTrue(elapsedMillis < 10_000, "the caller is released at the deadline, took " + elapsedMillis);
        } finally {
            release.countDown();
        }
    }

    @Test
    void anInterruptedWaitIsRefusedAndTheInterruptIsKept() {
        PolicyDecisionPoint slow = r -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return allow();
        };
        Thread.currentThread().interrupt();
        try {
            Decision result = guard(slow).decide(REQUEST);
            assertEquals(DecisionOutcome.ERROR, result.outcome());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status is restored for the caller");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theRecordedRequestIsTheOneTheCallerMade() {
        ScriptedPolicyDecisionPoint scripted = new ScriptedPolicyDecisionPoint(r -> allow());
        guard(scripted).decide(REQUEST);
        assertEquals(REQUEST, scripted.received().get(0));
    }

    @Test
    void theDeadlineMustBeBoundedAndPositive() {
        PolicyDecisionPoint any = r -> allow();
        assertThrows(IllegalArgumentException.class, () -> new FailClosedPolicyDecisionPoint(any, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new FailClosedPolicyDecisionPoint(any,
                Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> new FailClosedPolicyDecisionPoint(any,
                FailClosedPolicyDecisionPoint.MAX_TIMEOUT.plusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new FailClosedPolicyDecisionPoint(null, TIMEOUT));
    }

    @Test
    void theRequestItselfIsRequired() {
        assertThrows(NullPointerException.class, () -> guard(r -> allow()).decide(null));
    }
}
