package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The in-flight bulkhead of {@link FailClosedPolicyDecisionPoint}: delegates that ignore interruption can strand at
 * most {@code maxInFlight} virtual threads, and further calls are refused at once with a fail-closed ERROR.
 */
class FailClosedBulkheadTest {

    private static final AuthorizationRequest REQUEST = new AuthorizationRequest(Samples.USER,
            ActionKind.TOOL_CALL, "tool:case-search", Samples.TASK_ID);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private static Decision allow() {
        return new Decision("engine", DecisionOutcome.ALLOW, "permit matched", new PolicyRef("p", "1"), Samples.NOW);
    }

    /** Waits for the latch and keeps waiting through interrupts, as a delegate that ignores interruption does. */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** A delegate that holds every call until {@link #release()}, and ignores interruption while it holds. */
    private static final class StuckDelegate implements PolicyDecisionPoint {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger live = new AtomicInteger();
        private final AtomicInteger maxLive = new AtomicInteger();
        private final CountDownLatch entered;

        StuckDelegate(int expectedEntries) {
            this.entered = new CountDownLatch(expectedEntries);
        }

        @Override
        public Decision decide(AuthorizationRequest request) {
            invocations.incrementAndGet();
            maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                awaitIgnoringInterrupts(gate);
                return allow();
            } finally {
                live.decrementAndGet();
            }
        }

        void release() {
            gate.countDown();
        }
    }

    private static void assertBulkheadRefusal(Decision refused) {
        assertEquals(DecisionOutcome.ERROR, refused.outcome());
        assertFalse(refused.allows());
        assertTrue(refused.reason().contains("bulkhead"), "the reason must name the bulkhead: " + refused.reason());
        assertEquals(PolicyRef.FAIL_CLOSED, refused.policy());
    }

    @Test
    void theNextCallIsRefusedPromptlyWhenTheBoundIsExhaustedByStuckDelegates() throws Exception {
        int bound = 4;
        StuckDelegate stuck = new StuckDelegate(bound);
        FailClosedPolicyDecisionPoint guard = new FailClosedPolicyDecisionPoint(stuck, TIMEOUT, Clock.systemUTC(),
                bound);
        ExecutorService callers = Executors.newFixedThreadPool(bound);
        try {
            List<Future<Decision>> timedOut = new ArrayList<>();
            for (int i = 0; i < bound; i++) {
                timedOut.add(callers.submit(() -> guard.decide(REQUEST)));
            }
            assertTrue(stuck.entered.await(10, TimeUnit.SECONDS), "every stuck delegate should have started");

            long started = System.nanoTime();
            Decision refused = guard.decide(REQUEST);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertBulkheadRefusal(refused);
            assertTrue(elapsedMillis < TIMEOUT.toMillis() / 3,
                    "refusal must not wait for the deadline, took " + elapsedMillis + " ms");

            // The callers give up at the deadline, but the delegates are still running: the permits must stay held.
            for (Future<Decision> call : timedOut) {
                assertEquals(DecisionOutcome.ERROR, call.get(10, TimeUnit.SECONDS).outcome(),
                        "the stuck calls themselves time out as ERROR");
            }
            assertBulkheadRefusal(guard.decide(REQUEST));
        } finally {
            stuck.release();
            callers.shutdownNow();
        }
    }

    @Test
    void theLiveDelegateThreadsNeverExceedTheBoundAndRefusedCallsNeverReachTheDelegate() throws Exception {
        int bound = 3;
        StuckDelegate stuck = new StuckDelegate(bound);
        FailClosedPolicyDecisionPoint guard = new FailClosedPolicyDecisionPoint(stuck, TIMEOUT, Clock.systemUTC(),
                bound);
        ExecutorService callers = Executors.newFixedThreadPool(bound);
        try {
            List<Future<Decision>> timedOut = new ArrayList<>();
            for (int i = 0; i < bound; i++) {
                timedOut.add(callers.submit(() -> guard.decide(REQUEST)));
            }
            assertTrue(stuck.entered.await(10, TimeUnit.SECONDS));
            for (Future<Decision> call : timedOut) {
                call.get(10, TimeUnit.SECONDS);
            }

            // After every caller has given up, many more calls: each must be refused without starting a delegate.
            for (int i = 0; i < 200; i++) {
                assertBulkheadRefusal(guard.decide(REQUEST));
            }
            assertEquals(bound, stuck.invocations.get(), "refused calls must not call the delegate");
            assertEquals(bound, stuck.maxLive.get(), "live delegate threads are bounded by the configured maximum");
            assertEquals(bound, stuck.live.get());
        } finally {
            stuck.release();
            callers.shutdownNow();
        }
    }

    @Test
    void capacityRecoversOnceTheStuckDelegatesReturn() throws Exception {
        int bound = 2;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(bound);
        AtomicReference<PolicyDecisionPoint> behaviour = new AtomicReference<>(r -> {
            entered.countDown();
            awaitIgnoringInterrupts(gate);
            return allow();
        });
        PolicyDecisionPoint switchable = r -> behaviour.get().decide(r);
        FailClosedPolicyDecisionPoint guard = new FailClosedPolicyDecisionPoint(switchable, TIMEOUT,
                Clock.systemUTC(), bound);
        ExecutorService callers = Executors.newFixedThreadPool(bound);
        try {
            List<Future<Decision>> timedOut = new ArrayList<>();
            for (int i = 0; i < bound; i++) {
                timedOut.add(callers.submit(() -> guard.decide(REQUEST)));
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertBulkheadRefusal(guard.decide(REQUEST));
            for (Future<Decision> call : timedOut) {
                call.get(10, TimeUnit.SECONDS);
            }
            assertBulkheadRefusal(guard.decide(REQUEST));

            gate.countDown();
            behaviour.set(r -> allow());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!guard.decide(REQUEST).allows()) {
                assertTrue(System.nanoTime() < deadline, "capacity did not come back after the delegates returned");
                Thread.sleep(10);
            }

            // Every permit is back: a burst of `bound` callers must all be admitted at the same time. The delegate
            // holds each call until all of them are inside, so one missing permit would refuse a caller.
            for (int attempt = 0; ; attempt++) {
                CyclicBarrier together = new CyclicBarrier(bound);
                behaviour.set(r -> {
                    try {
                        together.await(2, TimeUnit.SECONDS);
                        return allow();
                    } catch (Exception e) {
                        return null;
                    }
                });
                List<Future<Decision>> burst = new ArrayList<>();
                for (int i = 0; i < bound; i++) {
                    burst.add(callers.submit(() -> guard.decide(REQUEST)));
                }
                boolean allAllowed = true;
                for (Future<Decision> call : burst) {
                    allAllowed &= call.get(10, TimeUnit.SECONDS).allows();
                }
                if (allAllowed) {
                    break;
                }
                assertTrue(attempt < 5, "the full bound was not available again");
                Thread.sleep(20);
            }
            for (Future<Decision> call : timedOut) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            gate.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void aConcurrentBurstNeverRunsMoreDelegatesThanTheBound() throws Exception {
        int bound = 8;
        int callersCount = 300;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        PolicyDecisionPoint briefly = r -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inside.decrementAndGet();
            }
            return allow();
        };
        FailClosedPolicyDecisionPoint guard = new FailClosedPolicyDecisionPoint(briefly, TIMEOUT,
                Clock.systemUTC(), bound);
        ConcurrentLinkedQueue<Decision> outcomes = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callersCount; i++) {
                callers.submit(() -> {
                    go.await();
                    outcomes.add(guard.decide(REQUEST));
                    return null;
                });
            }
            go.countDown();
        }

        assertEquals(callersCount, outcomes.size(), "every caller gets exactly one decision");
        long allowed = outcomes.stream().filter(Decision::allows).count();
        long refused = outcomes.stream().filter(d -> d.outcome() == DecisionOutcome.ERROR).count();
        assertEquals(callersCount, allowed + refused, "only ALLOW and ERROR come back");
        assertTrue(allowed > 0, "some calls must be admitted");
        assertTrue(maxInside.get() <= bound, "at most " + bound + " delegates ran at once, saw " + maxInside.get());
        assertEquals(0, inside.get(), "no delegate is still running after the burst");
    }

    @Test
    void theBoundIsValidatedAndTheDefaultIsWithinTheMaximum() {
        PolicyDecisionPoint any = r -> allow();
        assertThrows(IllegalArgumentException.class,
                () -> new FailClosedPolicyDecisionPoint(any, TIMEOUT, Clock.systemUTC(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FailClosedPolicyDecisionPoint(any, TIMEOUT, Clock.systemUTC(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new FailClosedPolicyDecisionPoint(any, TIMEOUT, Clock.systemUTC(),
                        FailClosedPolicyDecisionPoint.MAX_IN_FLIGHT + 1));
        assertTrue(new FailClosedPolicyDecisionPoint(any, TIMEOUT, Clock.systemUTC(),
                FailClosedPolicyDecisionPoint.MAX_IN_FLIGHT).decide(REQUEST).allows(), "the maximum itself is valid");
        assertTrue(FailClosedPolicyDecisionPoint.DEFAULT_MAX_IN_FLIGHT >= 1);
        assertTrue(FailClosedPolicyDecisionPoint.DEFAULT_MAX_IN_FLIGHT <= FailClosedPolicyDecisionPoint.MAX_IN_FLIGHT);
        assertThrows(NullPointerException.class,
                () -> new FailClosedPolicyDecisionPoint(null, TIMEOUT, Clock.systemUTC(), 1));
    }
}
