package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.time.Duration;
import java.util.Objects;

/**
 * Mandatory bounds on one agent task (M02): iterations of the agent loop, wall-clock time and expenditure. Every
 * limit is positive and at most a named maximum, so an unbounded task cannot be expressed.
 *
 * @param maxIterations  agent loop iterations, 1 to {@link #MAX_ITERATIONS}
 * @param maxWallClock   elapsed time from start, {@link #MIN_WALL_CLOCK} to {@link #MAX_WALL_CLOCK}
 * @param maxCostMicros  expenditure in millionths of the platform budget unit, 1 to {@link #MAX_COST_MICROS}
 */
public record ExecutionLimits(int maxIterations, Duration maxWallClock, long maxCostMicros) {

    public static final int MAX_ITERATIONS = 1_000;
    public static final Duration MIN_WALL_CLOCK = Duration.ofSeconds(1);
    public static final Duration MAX_WALL_CLOCK = Duration.ofHours(24);
    /** One million budget units, expressed in millionths. */
    public static final long MAX_COST_MICROS = 1_000_000_000_000L;

    public ExecutionLimits {
        Checks.requireInRange("maxIterations", maxIterations, 1, MAX_ITERATIONS);
        Objects.requireNonNull(maxWallClock, "maxWallClock");
        if (maxWallClock.compareTo(MIN_WALL_CLOCK) < 0 || maxWallClock.compareTo(MAX_WALL_CLOCK) > 0) {
            throw new IllegalArgumentException("maxWallClock must be between " + MIN_WALL_CLOCK + " and "
                    + MAX_WALL_CLOCK + ", was " + maxWallClock);
        }
        Checks.requireInRange("maxCostMicros", maxCostMicros, 1L, MAX_COST_MICROS);
    }

    /** True when every limit is at most the corresponding limit of {@code bound}. */
    public boolean isWithin(ExecutionLimits bound) {
        return maxIterations <= bound.maxIterations
                && maxWallClock.compareTo(bound.maxWallClock) <= 0
                && maxCostMicros <= bound.maxCostMicros;
    }
}
