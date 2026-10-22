package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.RunBudget.Limit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

/** The in-memory run budget: iterations, wall-clock and cost, enforced by reservation before the work. */
class RunBudgetTest {

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        final AtomicLong seconds = new AtomicLong();
        final Instant start = Instant.parse("2026-10-22T00:00:00Z");

        @Override
        public Instant instant() {
            return start.plusSeconds(seconds.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final MovingClock clock = new MovingClock();

    private RunBudget budget(int iterations, long seconds, long cost) {
        return new RunBudget(new ExecutionLimits(iterations, Duration.ofSeconds(seconds), cost), clock);
    }

    @Test
    void iterationsAreNumberedAndTheOnePastTheLimitIsRefused() {
        var b = budget(2, 60, 100);

        assertEquals(1, b.admitIteration());
        assertEquals(2, b.admitIteration());
        var e = assertThrows(BudgetExhausted.class, b::admitIteration);

        assertEquals(Limit.ITERATIONS, e.limit());
        assertEquals(2, b.iterationsUsed());
    }

    @Test
    void aCostThatDoesNotFitTakesNothing() {
        var b = budget(5, 60, 100);
        b.reserveCost(70);

        var e = assertThrows(BudgetExhausted.class, () -> b.reserveCost(31));

        assertEquals(Limit.COST, e.limit());
        assertEquals(30, b.costRemainingMicros());
        b.reserveCost(30);
        assertEquals(0, b.costRemainingMicros());
    }

    @Test
    void spentCostCanNeverPassTheLimit() {
        var b = budget(5, 60, 100);
        long spent = 0;
        for (long ask : List.of(40L, 40L, 40L, 20L, 1L)) {
            try {
                b.reserveCost(ask);
                spent += ask;
            } catch (BudgetExhausted expected) {
                // refused: nothing taken
            }
        }
        assertEquals(100 - spent, b.costRemainingMicros());
        assertTrue(spent <= 100);
    }

    @Test
    void afterTheWallClockLimitEverythingIsRefused() {
        var b = budget(5, 10, 100);
        clock.seconds.set(10);

        assertEquals(Limit.WALL_CLOCK, assertThrows(BudgetExhausted.class, b::admitIteration).limit());
        assertEquals(Limit.WALL_CLOCK, assertThrows(BudgetExhausted.class, () -> b.reserveCost(1)).limit());
        assertEquals(Optional.of(Limit.WALL_CLOCK), b.exhausted());
    }

    @Test
    void justInsideTheWallClockLimitStillWorks() {
        var b = budget(5, 10, 100);
        clock.seconds.set(9);

        assertEquals(1, b.admitIteration());
        assertEquals(Optional.empty(), b.exhausted());
    }

    @Test
    void exhaustedReportsTheReachedLimitWithoutChangingTheBudget() {
        var b = budget(1, 60, 100);
        b.admitIteration();
        assertEquals(Optional.of(Limit.ITERATIONS), b.exhausted());

        var c = budget(5, 60, 100);
        c.reserveCost(100);
        assertEquals(Optional.of(Limit.COST), c.exhausted());
        assertEquals(0, c.iterationsUsed());
    }

    @Test
    void zeroCostReservationsAreAllowedAndNegativeOnesAreNot() {
        var b = budget(5, 60, 100);
        b.reserveCost(0);
        assertThrows(IllegalArgumentException.class, () -> b.reserveCost(-1));
        assertEquals(100, b.costRemainingMicros());
    }

    @Test
    void concurrentReservationsNeverOverspend() throws Exception {
        var b = budget(5, 60, 1_000);
        var granted = new AtomicInteger();
        var threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int k = 0; k < 100; k++) {
                    try {
                        b.reserveCost(7);
                        granted.incrementAndGet();
                    } catch (BudgetExhausted expected) {
                        // refused
                    }
                }
            });
            threads[i].start();
        }
        for (var t : threads) {
            t.join();
        }
        assertEquals(1_000 - granted.get() * 7L, b.costRemainingMicros());
        assertEquals(142, granted.get());
    }

    @Test
    void theNodeInterceptorUsesTheBudgetAndNeverRunsTheNodeAfterIt() throws Exception {
        var audit = new NodeInterceptorTest.Audit();
        var node = new NodeInterceptorTest.Counting();
        var interceptor = new NodeInterceptor(GovernedLlmClientTest.AGENT, "run-1", budget(2, 60, 100), audit,
                GovernedLlmClientTest.CLOCK);
        var wrapped = interceptor.wrap("n", node);
        var state = new AgentState(Map.of());

        wrapped.apply(state);
        wrapped.apply(state);
        assertThrows(NodeRefused.class, () -> wrapped.apply(state));

        assertEquals(2, node.runs.get());
        assertEquals(2, interceptor.stepsCounted());
        assertEquals("agent.node.refused", audit.actions().get(audit.actions().size() - 1));
    }

    @Test
    void theNodeInterceptorRefusesAfterTheWallClockLimit() {
        var audit = new NodeInterceptorTest.Audit();
        var node = new NodeInterceptorTest.Counting();
        var b = budget(5, 10, 100);
        var interceptor = new NodeInterceptor(GovernedLlmClientTest.AGENT, "run-1", b, audit,
                GovernedLlmClientTest.CLOCK);
        clock.seconds.set(11);

        assertThrows(NodeRefused.class, () -> interceptor.wrap("n", node).apply(new AgentState(Map.of())));

        assertEquals(0, node.runs.get());
    }
}
