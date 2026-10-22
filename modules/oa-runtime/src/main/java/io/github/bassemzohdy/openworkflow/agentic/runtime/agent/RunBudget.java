package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The bounds of one agent run, enforced in memory (WP-202): loop iterations, wall-clock time and cost, taken from the
 * task's {@link ExecutionLimits}. One budget is made for one run and starts its clock when it is made.
 *
 * <p>Enforcement is by reservation, before the work:
 * <ul>
 *   <li>{@link #admitIteration()} counts an iteration and throws {@link BudgetExhausted} when the limit is already
 *       reached;</li>
 *   <li>{@link #reserveCost(long)} takes the cost of a call before it is made and throws when it does not fit, taking
 *       nothing, so spent cost never passes the limit;</li>
 *   <li>both throw once the wall-clock limit has passed.</li>
 * </ul>
 * A reservation is not refunded when the call then fails: the money may have been spent, and a failing call must not
 * be able to retry past the limit. Refused attempts are not counted.
 *
 * <p>This holds state in memory only. A run that restarts gets a new budget, so durable exhaustion decisions, which
 * survive a restart, are a separate step that needs a migration (see the WP-202 handoff). The thread-safe methods
 * are synchronized; the cost of holding the monitor is a few arithmetic operations.
 */
public final class RunBudget {

    /** The limit that was reached. */
    public enum Limit { ITERATIONS, WALL_CLOCK, COST }

    private final ExecutionLimits limits;
    private final Clock clock;
    private final Instant startedAt;
    private int iterations;
    private long costMicros;

    public RunBudget(ExecutionLimits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
    }

    /** Counts one iteration and returns its number (starting at 1), or throws {@link BudgetExhausted}. */
    public synchronized int admitIteration() {
        requireTimeLeft();
        if (iterations >= limits.maxIterations()) {
            throw new BudgetExhausted(Limit.ITERATIONS, "iteration limit " + limits.maxIterations() + " reached");
        }
        return ++iterations;
    }

    /** Reserves {@code micros} of cost before a call; takes nothing and throws when it does not fit. */
    public synchronized void reserveCost(long micros) {
        Checks.requireInRange("micros", micros, 0L, ExecutionLimits.MAX_COST_MICROS);
        requireTimeLeft();
        if (micros > costRemainingMicros()) {
            throw new BudgetExhausted(Limit.COST,
                    "cost " + micros + " exceeds the " + costRemainingMicros() + " micros left");
        }
        costMicros += micros;
    }

    /** Cost left, never negative. This is the value for {@code ApprovedModelContext.Approved#budgetRemainingMicros}. */
    public synchronized long costRemainingMicros() {
        return limits.maxCostMicros() - costMicros;
    }

    public synchronized int iterationsUsed() {
        return iterations;
    }

    /** The limit already reached, if any. Does not change the budget. */
    public synchronized Optional<Limit> exhausted() {
        if (!timeLeft()) {
            return Optional.of(Limit.WALL_CLOCK);
        }
        if (iterations >= limits.maxIterations()) {
            return Optional.of(Limit.ITERATIONS);
        }
        if (costMicros >= limits.maxCostMicros()) {
            return Optional.of(Limit.COST);
        }
        return Optional.empty();
    }

    private boolean timeLeft() {
        return Duration.between(startedAt, clock.instant()).compareTo(limits.maxWallClock()) < 0;
    }

    private void requireTimeLeft() {
        if (!timeLeft()) {
            throw new BudgetExhausted(Limit.WALL_CLOCK, "wall-clock limit " + limits.maxWallClock() + " reached");
        }
    }
}
