package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExecutionLimitsTest {

    @Test
    void boundsAreInclusive() {
        new ExecutionLimits(1, Duration.ofSeconds(1), 1L);
        new ExecutionLimits(ExecutionLimits.MAX_ITERATIONS, ExecutionLimits.MAX_WALL_CLOCK,
                ExecutionLimits.MAX_COST_MICROS);
    }

    @Test
    void zeroAndNegativeIterationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(0, Duration.ofMinutes(1), 1L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(-1, Duration.ofMinutes(1), 1L));
    }

    @Test
    void absurdlyLargeIterationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(
                ExecutionLimits.MAX_ITERATIONS + 1, Duration.ofMinutes(1), 1L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(
                Integer.MAX_VALUE, Duration.ofMinutes(1), 1L));
    }

    @Test
    void wallClockMustBeWithinTheBounds() {
        assertThrows(NullPointerException.class, () -> new ExecutionLimits(1, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1, Duration.ZERO, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1, Duration.ofMillis(999), 1L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1, Duration.ofSeconds(-5), 1L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1,
                ExecutionLimits.MAX_WALL_CLOCK.plusSeconds(1), 1L));
    }

    @Test
    void costMustBePositiveAndBounded() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1, Duration.ofMinutes(1), 0L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1, Duration.ofMinutes(1), -1L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1, Duration.ofMinutes(1),
                ExecutionLimits.MAX_COST_MICROS + 1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(1, Duration.ofMinutes(1),
                Long.MAX_VALUE));
    }

    @Test
    void isWithinComparesEveryLimit() {
        ExecutionLimits bound = new ExecutionLimits(10, Duration.ofMinutes(10), 100L);
        assertTrue(bound.isWithin(bound));
        assertTrue(new ExecutionLimits(9, Duration.ofMinutes(9), 99L).isWithin(bound));
        assertFalse(new ExecutionLimits(11, Duration.ofMinutes(1), 1L).isWithin(bound));
        assertFalse(new ExecutionLimits(1, Duration.ofMinutes(11), 1L).isWithin(bound));
        assertFalse(new ExecutionLimits(1, Duration.ofMinutes(1), 101L).isWithin(bound));
    }
}
