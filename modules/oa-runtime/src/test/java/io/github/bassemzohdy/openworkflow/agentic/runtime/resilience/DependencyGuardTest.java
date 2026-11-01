package io.github.bassemzohdy.openworkflow.agentic.runtime.resilience;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.runtime.resilience.DependencyGuard.Rejection;
import io.github.bassemzohdy.openworkflow.agentic.runtime.resilience.DependencyGuard.RejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.resilience.DependencyGuard.Settings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.resilience.DependencyGuard.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DependencyGuardTest {

    static final class MovingClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-01T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final MovingClock clock = new MovingClock();
    private final List<Duration> slept = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach void tearDown() {
        pool.shutdownNow();
        Thread.interrupted();
    }

    private static Settings settings(int concurrent, int threshold, int budget) {
        return new Settings("dep", concurrent, threshold, Duration.ofSeconds(30), budget, Duration.ofMinutes(1),
                Duration.ofMillis(50));
    }

    private DependencyGuard guard(int concurrent, int threshold, int budget) {
        return new DependencyGuard(settings(concurrent, threshold, budget), clock, slept::add);
    }

    private static <T> T boom(String message) { throw new IllegalStateException(message); }

    private void fail(DependencyGuard guard, int times) {
        for (int i = 0; i < times; i++) assertThrows(IllegalStateException.class, () -> guard.call(() -> boom("down")));
    }

    // ---- load shedding -----------------------------------------------------------------------------------

    @Test void aCallBeyondTheConcurrencyBoundIsShedAtOnceWithoutQueueing() throws Exception {
        var guard = guard(2, 5, 3);
        var running = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        List<Future<String>> held = new ArrayList<>();
        for (int i = 0; i < 2; i++) held.add(pool.submit(() -> guard.call(() -> {
            running.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "ok";
        })));
        assertTrue(running.await(5, TimeUnit.SECONDS));

        var shed = new AtomicInteger();
        var third = new AtomicInteger();
        var e = assertThrows(RejectedException.class, () -> guard.call(() -> { third.incrementAndGet(); return "x"; }));
        assertEquals(Rejection.OVERLOADED, e.reason());
        assertEquals(0, third.get(), "the shed call never reached the dependency");
        shed.incrementAndGet();

        release.countDown();
        for (var f : held) assertEquals("ok", f.get(5, TimeUnit.SECONDS));
        assertEquals("again", guard.call(() -> "again"), "permits are returned once the calls finish");
        assertEquals(1, guard.stats().rejectedOverload());
        assertEquals(State.CLOSED, guard.state(), "shedding is not a failure and never opens the circuit");
    }

    @Test void thePermitIsReturnedWhenTheDependencyThrows() {
        var guard = guard(1, 100, 0);
        for (int i = 0; i < 5; i++) assertThrows(IllegalStateException.class, () -> guard.call(() -> boom("x")));
        assertEquals("fine", guard.call(() -> "fine"));
    }

    // ---- circuit breaker ---------------------------------------------------------------------------------

    @Test void consecutiveFailuresOpenTheCircuitAndTheDependencyIsNotTouchedWhileOpen() {
        var guard = guard(4, 3, 0);
        fail(guard, 3);
        assertEquals(State.OPEN, guard.state());

        var touched = new AtomicInteger();
        var e = assertThrows(RejectedException.class, () -> guard.call(() -> { touched.incrementAndGet(); return "x"; }));
        assertEquals(Rejection.CIRCUIT_OPEN, e.reason());
        assertEquals(0, touched.get());
        assertEquals(1, guard.stats().rejectedCircuit());
    }

    @Test void aSuccessResetsTheConsecutiveFailureCount() {
        var guard = guard(4, 3, 0);
        fail(guard, 2);
        assertEquals("ok", guard.call(() -> "ok"));
        fail(guard, 2);
        assertEquals(State.CLOSED, guard.state(), "two, success, two never reaches three in a row");
    }

    @Test void afterTheOpenPeriodOneProbeIsAllowedAndItsSuccessClosesTheCircuit() {
        var guard = guard(4, 2, 0);
        fail(guard, 2);
        clock.advance(Duration.ofSeconds(29));
        assertThrows(RejectedException.class, () -> guard.call(() -> "x"));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(State.HALF_OPEN, guard.state());

        assertEquals("probe", guard.call(() -> "probe"));

        assertEquals(State.CLOSED, guard.state());
        assertEquals("normal", guard.call(() -> "normal"));
    }

    @Test void aFailedProbeReopensTheCircuitForAnotherFullPeriod() {
        var guard = guard(4, 2, 0);
        fail(guard, 2);
        clock.advance(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, () -> guard.call(() -> boom("still down")));
        assertEquals(State.OPEN, guard.state());
        clock.advance(Duration.ofSeconds(29));
        assertThrows(RejectedException.class, () -> guard.call(() -> "x"));
        clock.advance(Duration.ofSeconds(1));
        assertEquals("back", guard.call(() -> "back"));
    }

    @Test void onlyOneProbeRunsWhileHalfOpenAndOthersAreRejected() throws Exception {
        var guard = guard(4, 2, 0);
        fail(guard, 2);
        clock.advance(Duration.ofSeconds(30));
        var probeRunning = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        Future<String> probe = pool.submit(() -> guard.call(() -> {
            probeRunning.countDown();
            try { finish.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "probe";
        }));
        assertTrue(probeRunning.await(5, TimeUnit.SECONDS));

        var other = assertThrows(RejectedException.class, () -> guard.call(() -> "second"));
        assertEquals(Rejection.CIRCUIT_OPEN, other.reason());

        finish.countDown();
        assertEquals("probe", probe.get(5, TimeUnit.SECONDS));
        assertEquals(State.CLOSED, guard.state());
    }

    @Test void aProbeShedByTheBulkheadDoesNotLeaveTheCircuitStuckHalfOpen() throws Exception {
        var guard = guard(1, 2, 0);
        fail(guard, 2);
        clock.advance(Duration.ofSeconds(30));
        var running = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        Future<String> holder = pool.submit(() -> guard.call(() -> {
            running.countDown();
            try { finish.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "probe";
        }));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        var e = assertThrows(RejectedException.class, () -> guard.call(() -> "x"));
        assertEquals(Rejection.OVERLOADED, e.reason());
        finish.countDown();
        assertEquals("probe", holder.get(5, TimeUnit.SECONDS));
        assertEquals("ok", guard.call(() -> "ok"), "the circuit recovered through the first probe");
    }

    // ---- retry budget ------------------------------------------------------------------------------------

    @Test void aRetriableFailureIsRetriedUpToTheAttemptsWithABackoffBetween() {
        var guard = guard(4, 100, 10);
        var calls = new AtomicInteger();
        String result = guard.callWithRetry(3, t -> t instanceof IllegalStateException, () -> {
            if (calls.incrementAndGet() < 3) return boom("transient");
            return "done";
        });
        assertEquals("done", result);
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofMillis(50), Duration.ofMillis(50)), slept);
        assertEquals(2, guard.stats().retries());
    }

    @Test void theLastFailureIsThrownUnchangedWhenAttemptsRunOut() {
        var guard = guard(4, 100, 10);
        var calls = new AtomicInteger();
        var e = assertThrows(IllegalStateException.class,
                () -> guard.callWithRetry(2, t -> true, () -> boom("always " + calls.incrementAndGet())));
        assertEquals("always 2", e.getMessage());
    }

    @Test void aNonRetriableFailureIsNeverRetried() {
        var guard = guard(4, 100, 10);
        var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> guard.callWithRetry(5, t -> t instanceof IllegalStateException,
                () -> { calls.incrementAndGet(); throw new IllegalArgumentException("bad request"); }));
        assertEquals(1, calls.get());
        assertTrue(slept.isEmpty());
    }

    @Test void theRetryBudgetBoundsRetriesAcrossCallsAndRefillsEachWindow() {
        var guard = guard(4, 1000, 2);
        var calls = new AtomicInteger();
        // three calls, each wanting one retry, but the budget allows two retries in the window
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class,
                    () -> guard.callWithRetry(2, t -> true, () -> { calls.incrementAndGet(); return boom("down"); }));
        }
        assertEquals(5, calls.get(), "3 first attempts plus only 2 budgeted retries");
        assertEquals(2, guard.stats().retries());
        assertEquals(1, guard.stats().retriesDenied());

        clock.advance(Duration.ofMinutes(1));
        calls.set(0);
        assertThrows(IllegalStateException.class,
                () -> guard.callWithRetry(2, t -> true, () -> { calls.incrementAndGet(); return boom("down"); }));
        assertEquals(2, calls.get(), "a new window grants the budget again");
    }

    @Test void aRejectionIsNeverRetriedAndAnOpenCircuitStopsRetryingAtOnce() {
        var guard = guard(4, 2, 100);
        var calls = new AtomicInteger();
        var e = assertThrows(RejectedException.class, () -> guard.callWithRetry(5, t -> true, () -> {
            calls.incrementAndGet();
            return boom("down");
        }));
        assertEquals(Rejection.CIRCUIT_OPEN, e.reason());
        assertEquals(2, calls.get(), "two failures opened the circuit; the third attempt was rejected, not run");
    }

    @Test void anInterruptWhilePausingStopsRetryingKeepsTheFlagAndThrowsTheLastFailure() {
        var interrupting = new DependencyGuard(settings(4, 100, 10), clock, d -> { throw new InterruptedException(); });
        var calls = new AtomicInteger();
        var e = assertThrows(IllegalStateException.class,
                () -> interrupting.callWithRetry(5, t -> true, () -> { calls.incrementAndGet(); return boom("down"); }));
        assertEquals("down", e.getMessage());
        assertEquals(1, calls.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    // ---- counters and validation -------------------------------------------------------------------------

    @Test void countersTellSuccessesFailuresAndRejectionsApart() {
        var guard = guard(4, 2, 0);
        guard.call(() -> "ok");
        fail(guard, 2);
        assertThrows(RejectedException.class, () -> guard.call(() -> "x"));
        var stats = guard.stats();
        assertEquals(1, stats.successes());
        assertEquals(2, stats.failures());
        assertEquals(1, stats.rejectedCircuit());
        assertEquals(0, stats.rejectedOverload());
    }

    @Test void settingsAndArgumentsAreValidated() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new Settings(" ", 1, 1, d, 0, d, d));
        assertThrows(IllegalArgumentException.class, () -> new Settings("n", 0, 1, d, 0, d, d));
        assertThrows(IllegalArgumentException.class, () -> new Settings("n", 1, 0, d, 0, d, d));
        assertThrows(IllegalArgumentException.class, () -> new Settings("n", 1, 1, Duration.ZERO, 0, d, d));
        assertThrows(IllegalArgumentException.class, () -> new Settings("n", 1, 1, d, -1, d, d));
        assertThrows(IllegalArgumentException.class, () -> new Settings("n", 1, 1, d, 0, Duration.ZERO, d));
        assertThrows(IllegalArgumentException.class, () -> new Settings("n", 1, 1, d, 0, d, Duration.ofSeconds(-1)));
        var guard = guard(1, 1, 1);
        assertThrows(NullPointerException.class, () -> guard.call(null));
        assertThrows(IllegalArgumentException.class, () -> guard.callWithRetry(0, t -> true, () -> "x"));
        assertThrows(IllegalArgumentException.class, () -> guard.callWithRetry(11, t -> true, () -> "x"));
        assertThrows(NullPointerException.class, () -> guard.callWithRetry(1, null, () -> "x"));
    }
}
