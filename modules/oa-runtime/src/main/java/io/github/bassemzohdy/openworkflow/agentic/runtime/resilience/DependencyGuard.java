package io.github.bassemzohdy.openworkflow.agentic.runtime.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Protects one dependency boundary (WP-110): bounded concurrency with immediate load shedding, a circuit breaker and a
 * retry budget. It is deliberately small and deterministic (injectable clock and sleeper) and adds no thread, queue or
 * timer of its own.
 *
 * <ul>
 *   <li><b>Load shedding.</b> At most {@code maxConcurrent} calls run at once. The next one is rejected at once with
 *       {@link Rejection#OVERLOADED}; nothing waits in a queue, so overload cannot pile up unbounded work.</li>
 *   <li><b>Circuit breaker.</b> After {@code failureThreshold} consecutive failures the circuit opens and every call is
 *       rejected with {@link Rejection#CIRCUIT_OPEN} without touching the dependency, for {@code openFor}. Then exactly
 *       one probe is let through (half-open): its success closes the circuit, its failure reopens it for another
 *       {@code openFor}. Results of calls admitted before opening do not affect a later circuit generation. A rejected
 *       call is neither a success nor a failure.</li>
 *   <li><b>Retry budget.</b> {@link #callWithRetry} retries only while the budget of {@code retryBudget} retries per
 *       {@code retryWindow} lasts, so a failing dependency cannot be multiplied by retries. Rejections are never
 *       retried. Each retry passes the bulkhead and breaker again.</li>
 * </ul>
 *
 * <p><b>Use only for operations that are safe to repeat or to skip.</b> A call that changes an external system must
 * not be retried here: that is the effect ledger's job (write-ahead intent, stable key, unknown-outcome
 * reconciliation). The guard never swallows the dependency's failure: it propagates unchanged and is only counted.
 */
public final class DependencyGuard {

    /** Why a call was refused without reaching the dependency. */
    public enum Rejection { OVERLOADED, CIRCUIT_OPEN }

    /** The guard refused the call; the dependency was not touched. */
    public static final class RejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Rejection reason;

        RejectedException(String boundary, Rejection reason) {
            super("dependency " + boundary + " refused the call: " + reason);
            this.reason = reason;
        }

        public Rejection reason() { return reason; }
    }

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Pauses between retries; the default sleeps the thread. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public record Settings(String name, int maxConcurrent, int failureThreshold, Duration openFor, int retryBudget,
                           Duration retryWindow, Duration retryBackoff) {
        public Settings {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (maxConcurrent < 1 || maxConcurrent > 10_000) throw new IllegalArgumentException("maxConcurrent must be 1..10000");
            if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be at least 1");
            positive("openFor", openFor);
            if (retryBudget < 0) throw new IllegalArgumentException("retryBudget must not be negative");
            positive("retryWindow", retryWindow);
            Objects.requireNonNull(retryBackoff, "retryBackoff");
            if (retryBackoff.isNegative()) throw new IllegalArgumentException("retryBackoff must not be negative");
        }

        private static void positive(String field, Duration value) {
            Objects.requireNonNull(value, field);
            if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        }
    }

    /** Counters since the guard was made. */
    public record Stats(long successes, long failures, long rejectedOverload, long rejectedCircuit, long retries,
                        long retriesDenied) { }

    private final Settings settings;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Semaphore permits;
    private final Object lock = new Object();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong rejectedOverload = new AtomicLong();
    private final AtomicLong rejectedCircuit = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong retriesDenied = new AtomicLong();

    // guarded by lock
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long generation;
    private Instant openedAt;
    private boolean probeInFlight;
    private Instant windowStart;
    private int retriesInWindow;

    public DependencyGuard(Settings settings, Clock clock) {
        this(settings, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    public DependencyGuard(Settings settings, Clock clock, Sleeper sleeper) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.permits = new Semaphore(settings.maxConcurrent());
        this.windowStart = clock.instant();
    }

    /** The current breaker state. An elapsed open period reads as {@link State#HALF_OPEN}. */
    public State state() {
        synchronized (lock) {
            return effectiveState(clock.instant());
        }
    }

    public Stats stats() {
        return new Stats(successes.get(), failures.get(), rejectedOverload.get(), rejectedCircuit.get(), retries.get(),
                retriesDenied.get());
    }

    /** Calls the dependency once through the bulkhead and the breaker; every failure counts against the dependency. */
    public <T> T call(Supplier<T> operation) {
        return call(operation, failure -> true);
    }

    /**
     * Calls the dependency once. A failure for which {@code countsAsFailure} is false (a cancellation, a caller's own
     * bad input) is rethrown but is neither a success nor a failure for the breaker, and a half-open probe that ends this
     * way leaves the circuit half-open for the next call.
     */
    public <T> T call(Supplier<T> operation, Predicate<Throwable> countsAsFailure) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(countsAsFailure, "countsAsFailure");
        if (!permits.tryAcquire()) {
            rejectedOverload.incrementAndGet();
            throw new RejectedException(settings.name(), Rejection.OVERLOADED);
        }
        try {
            Admission admission = admit();
            try {
                T result = operation.get();
                recordSuccess(admission);
                return result;
            } catch (Throwable failure) {
                boolean counts;
                try {
                    counts = countsAsFailure.test(failure);
                } catch (Throwable classifierFailure) {
                    recordNeutral(admission);
                    throw classifierFailure;
                }
                if (counts) recordFailure(admission); else recordNeutral(admission);
                throw failure;
            }
        } finally {
            permits.release();
        }
    }

    /**
     * Calls the dependency, retrying a failure that {@code retriable} accepts while attempts and the retry budget last,
     * pausing {@code retryBackoff} between attempts. A rejection is thrown at once and never retried. The last failure is
     * thrown unchanged when retrying stops; an interrupt while pausing stops retrying, keeps the interrupt flag and
     * throws the last failure.
     */
    public <T> T callWithRetry(int maxAttempts, Predicate<Throwable> retriable, Supplier<T> operation) {
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("maxAttempts must be 1..10");
        Objects.requireNonNull(retriable, "retriable");
        Objects.requireNonNull(operation, "operation");
        for (int attempt = 1; ; attempt++) {
            try {
                return call(operation);
            } catch (RejectedException rejected) {
                throw rejected;
            } catch (Throwable failure) {
                if (attempt >= maxAttempts || !retriable.test(failure)) throw failure;
                if (!consumeRetry()) {
                    retriesDenied.incrementAndGet();
                    throw failure;
                }
                retries.incrementAndGet();
                if (!settings.retryBackoff().isZero()) {
                    try {
                        sleeper.sleep(settings.retryBackoff());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw failure;
                    }
                }
            }
        }
    }

    // ---- breaker ---------------------------------------------------------------------------------------------

    private State effectiveState(Instant now) {
        if (state == State.OPEN && !now.isBefore(openedAt.plus(settings.openFor()))) return State.HALF_OPEN;
        return state;
    }

    private record Admission(boolean probe, long generation) { }

    private Admission admit() {
        synchronized (lock) {
            Instant now = clock.instant();
            State current = effectiveState(now);
            if (current == State.CLOSED) return new Admission(false, generation);
            if (current == State.HALF_OPEN && !probeInFlight) {
                state = State.HALF_OPEN;
                probeInFlight = true;
                return new Admission(true, generation);
            }
            rejectedCircuit.incrementAndGet();
            throw new RejectedException(settings.name(), Rejection.CIRCUIT_OPEN);
        }
    }

    private void recordSuccess(Admission admission) {
        successes.incrementAndGet();
        synchronized (lock) {
            if (admission.generation() != generation) return;
            consecutiveFailures = 0;
            if (admission.probe()) {
                probeInFlight = false;
                state = State.CLOSED;
                openedAt = null;
            }
        }
    }

    private void recordNeutral(Admission admission) {
        synchronized (lock) {
            if (admission.probe() && admission.generation() == generation) probeInFlight = false;
        }
    }

    private void recordFailure(Admission admission) {
        failures.incrementAndGet();
        synchronized (lock) {
            if (admission.generation() != generation) return;
            if (admission.probe()) {
                probeInFlight = false;
                open();
            } else if (state == State.CLOSED && ++consecutiveFailures >= settings.failureThreshold()) {
                open();
            }
        }
    }

    private void open() {
        generation++;
        state = State.OPEN;
        openedAt = clock.instant();
        consecutiveFailures = 0;
    }

    // ---- retry budget ----------------------------------------------------------------------------------------

    private boolean consumeRetry() {
        synchronized (lock) {
            Instant now = clock.instant();
            if (!now.isBefore(windowStart.plus(settings.retryWindow()))) {
                windowStart = now;
                retriesInWindow = 0;
            }
            if (retriesInWindow >= settings.retryBudget()) return false;
            retriesInWindow++;
            return true;
        }
    }
}
