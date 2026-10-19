package io.github.bassemzohdy.openworkflow.agentic.testkit.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} that only moves when a test moves it. Views made with {@link #withZone} share the same time.
 *
 * <p>Use it wherever code under test takes a clock: deadlines, token expiry, timers. Thread-safe.
 */
public final class ControllableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public ControllableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneId.of("UTC"));
    }

    private ControllableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /** A clock starting at 2026-10-18T12:00:00Z, the date the plan was written. */
    public static ControllableClock atPlanDate() {
        return new ControllableClock(Instant.parse("2026-10-18T12:00:00Z"));
    }

    /** Moves the time forward by {@code by}. Negative durations are rejected: time does not run backwards. */
    public void advance(Duration by) {
        if (by.isNegative()) {
            throw new IllegalArgumentException("a controllable clock does not go backwards: " + by);
        }
        now.updateAndGet(t -> t.plus(by));
    }

    /** Sets the time. Going backwards is allowed, because a test may need to rebuild an earlier state. */
    public void setInstant(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new ControllableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
