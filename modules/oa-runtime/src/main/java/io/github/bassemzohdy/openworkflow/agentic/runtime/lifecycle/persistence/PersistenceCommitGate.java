package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The SDK logs/swallow exceptional lifecycle futures. A failed required checkpoint must instead keep its stage
 * pending, without holding a thread or DB connection, until the process is restored from durable state. No failed
 * or late/unknown commit is converted into successful progress. Cancellation writes remain independently usable.
 */
@ApplicationScoped
public class PersistenceCommitGate implements AutoCloseable {
    public enum Operation { STARTED, COMPLETED, FAILED, ABORTED, SUSPENDED, RESUMED, TASK_RETRIED, TASK_STARTED, TASK_COMPLETED }
    public record Failure(Operation operation, String errorClass, Instant observedAt) { }
    public record Status(boolean healthy, int inFlight, long heldCalls, Optional<Failure> failure) { }

    private final Duration timeout;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicReference<Failure> failure = new AtomicReference<>();
    private final CompletableFuture<Failure> firstFailure = new CompletableFuture<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong heldCalls = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ProtectedFuture held = new ProtectedFuture();

    @Inject
    public PersistenceCommitGate(@ConfigProperty(name = "agentic.persistence.commit-timeout", defaultValue = "PT30S") Duration timeout) {
        this(timeout, Clock.systemUTC());
    }
    public PersistenceCommitGate(Duration timeout, Clock clock) {
        this.timeout = Objects.requireNonNull(timeout); this.clock = Objects.requireNonNull(clock);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("checkpoint timeout must be positive and at most five minutes");
        }
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "checkpoint-commit-watchdog"); thread.setDaemon(true); return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
    }

    public Status status() { return new Status(!closed.get() && failure.get() == null, inFlight.get(), heldCalls.get(), Optional.ofNullable(failure.get())); }
    CompletableFuture<Failure> firstFailure() { return firstFailure; }

    public void requireAvailable() {
        if (!status().healthy()) throw new StoreUnavailableException("workflow checkpoint recovery is required",
                new IllegalStateException("restore from durable state after checkpoint storage is healthy"));
    }

    /** Success observer is run only after actual writer success, never merely after another lifecycle listener. */
    public CompletableFuture<Void> commit(Operation operation, Supplier<CompletableFuture<Void>> write, Runnable committed) {
        Objects.requireNonNull(operation); Objects.requireNonNull(write); Objects.requireNonNull(committed);
        if (closed.get() || (failure.get() != null && operation != Operation.ABORTED)) {
            heldCalls.incrementAndGet(); return held;
        }
        ProtectedFuture result = new ProtectedFuture();
        // 0 awaiting writer, 1 writer won, 2 timeout/failure/closure: a late commit cannot release this stage.
        AtomicInteger phase = new AtomicInteger();
        ScheduledFuture<?> watchdog;
        try {
            watchdog = timer.schedule(() -> {
                if (phase.compareAndSet(0, 2)) fail(operation, "CheckpointTimeout");
            }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            heldCalls.incrementAndGet(); return held;
        }
        inFlight.incrementAndGet();
        try {
            CompletableFuture<Void> actual = Objects.requireNonNull(write.get(), "checkpoint writer future");
            actual.whenComplete((ignored, error) -> {
                inFlight.decrementAndGet(); watchdog.cancel(false);
                if (!phase.compareAndSet(0, error == null ? 1 : 2)) return;
                if (error != null) { fail(operation, error.getClass().getSimpleName()); return; }
                if (closed.get()) return;
                try { committed.run(); result.succeed(); }
                catch (RuntimeException observerFailure) { phase.set(2); fail(operation, observerFailure.getClass().getSimpleName()); }
            });
        } catch (RuntimeException writeFailure) {
            inFlight.decrementAndGet(); watchdog.cancel(false);
            if (phase.compareAndSet(0, 2)) fail(operation, writeFailure.getClass().getSimpleName());
        }
        return result;
    }

    private void fail(Operation operation, String errorClass) {
        Failure observed = new Failure(operation, errorClass, clock.instant());
        if (failure.compareAndSet(null, observed)) firstFailure.complete(observed);
    }

    @Override @PreDestroy
    public void close() { if (closed.compareAndSet(false, true)) timer.shutdownNow(); }

    /** Consumers may compose on the stage, but cancellation/timeouts cannot forge a successful commit. */
    private static final class ProtectedFuture extends CompletableFuture<Void> {
        void succeed() { super.complete(null); }
        @Override public boolean complete(Void ignored) { return false; }
        @Override public boolean completeExceptionally(Throwable error) { return false; }
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public void obtrudeValue(Void ignored) { throw new UnsupportedOperationException("checkpoint stage is read-only"); }
        @Override public void obtrudeException(Throwable error) { throw new UnsupportedOperationException("checkpoint stage is read-only"); }
        @Override public CompletableFuture<Void> completeAsync(Supplier<? extends Void> supplier) { throw new UnsupportedOperationException("checkpoint stage is read-only"); }
        @Override public CompletableFuture<Void> completeAsync(Supplier<? extends Void> supplier, java.util.concurrent.Executor executor) { throw new UnsupportedOperationException("checkpoint stage is read-only"); }
    }
}
