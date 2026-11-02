package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded physical exchange workers. A timed-out worker retains its slot until it actually exits. */
final class BoundedExchangeCalls implements AutoCloseable {
    private final ThreadPoolExecutor workers;

    BoundedExchangeCalls(int capacity) {
        if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("exchange concurrency must be 1..1024");
        workers = new ThreadPoolExecutor(0, capacity, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            var thread = new Thread(task, "identity-token-exchange");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    <T> T call(Callable<T> operation, Duration timeout) throws InterruptedException, TimeoutException, ExecutionException {
        return call(operation, timeout, new AtomicBoolean());
    }

    <T> T call(Callable<T> operation, Duration timeout, AtomicBoolean timedOut)
            throws InterruptedException, TimeoutException, ExecutionException {
        Objects.requireNonNull(timedOut, "timedOut");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("exchange interrupted");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        long nanos;
        try { nanos = timeout.toNanos(); } catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
        Future<T> future = workers.submit(operation);
        try {
            return future.get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            timedOut.set(true);
            future.cancel(true);
            throw failure;
        } catch (InterruptedException failure) {
            future.cancel(true);
            throw failure;
        }
    }

    @Override public void close() { workers.shutdownNow(); }
}
