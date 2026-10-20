package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PersistenceCommitGate.Operation.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PersistenceCommitGateTest {
    private static PersistenceCommitGate gate() { return new PersistenceCommitGate(Duration.ofSeconds(5), Clock.systemUTC()); }

    @Test
    void onlyTheActualWriterCommitCanReleaseLifecycleProgress() {
        try (var gate = gate()) {
            var write = new CompletableFuture<Void>();
            var observed = new AtomicInteger(); var advanced = new AtomicInteger();
            var stage = gate.commit(TASK_COMPLETED, () -> write, observed::incrementAndGet);
            // The SDK swallows exceptional lifecycle stages. This continuation must stay pending on failure.
            stage.exceptionally(error -> null).thenRun(advanced::incrementAndGet);
            assertEquals(1, gate.status().inFlight());
            assertFalse(stage.complete(null)); assertFalse(stage.completeExceptionally(new RuntimeException()));
            assertFalse(stage.cancel(true));
            assertThrows(UnsupportedOperationException.class, () -> stage.obtrudeValue(null));
            assertEquals(0, advanced.get()); assertEquals(0, observed.get());
            write.complete(null);
            assertTrue(stage.isDone()); assertEquals(1, observed.get()); assertEquals(1, advanced.get());
            assertEquals(0, gate.status().inFlight()); assertTrue(gate.status().healthy());
        }
    }

    @Test
    void failuresStayPendingInsteadOfBeingSwallowedIntoForwardProgress() {
        for (Supplier<CompletableFuture<Void>> writer : java.util.List.<Supplier<CompletableFuture<Void>>>of(
                () -> CompletableFuture.failedFuture(new IllegalStateException("do not publish this message")),
                () -> { throw new IllegalArgumentException("private payload"); }, () -> null,
                () -> { var cancelled = new CompletableFuture<Void>(); cancelled.cancel(true); return cancelled; })) {
            try (var gate = gate()) {
                var advanced = new AtomicInteger();
                var stage = gate.commit(TASK_COMPLETED, writer, () -> fail("no commit observed"));
                stage.exceptionally(error -> null).thenRun(advanced::incrementAndGet);
                assertFalse(stage.isDone()); assertEquals(0, advanced.get());
                assertFalse(gate.status().healthy()); assertEquals(0, gate.status().inFlight());
                assertFalse(gate.status().toString().contains("private payload"));
                assertFalse(gate.status().toString().contains("do not publish"));
                assertThrows(StoreUnavailableException.class, gate::requireAvailable);
                assertFalse(gate.commit(TASK_STARTED, () -> { fail("failed gate must not dispatch"); return null; }, () -> {}).isDone());
                assertEquals(1, gate.status().heldCalls());
                // Operator cancellation may still persist; it does not make the unhealthy process ready again.
                assertTrue(gate.commit(ABORTED, () -> CompletableFuture.completedFuture(null), () -> {}).isDone());
                assertFalse(gate.status().healthy());
            }
        }
    }

    @Test
    void timeoutNeverTurnsALateCommitIntoSuccessfulDelivery() throws Exception {
        try (var gate = new PersistenceCommitGate(Duration.ofMillis(20), Clock.systemUTC())) {
            var actual = new CompletableFuture<Void>();
            var observed = new AtomicInteger();
            var stage = gate.commit(TASK_COMPLETED, () -> actual, observed::incrementAndGet);
            assertEquals("CheckpointTimeout", gate.firstFailure().get(5, TimeUnit.SECONDS).errorClass());
            actual.complete(null);
            assertFalse(stage.isDone()); assertEquals(0, observed.get());
            assertEquals(0, gate.status().inFlight()); assertFalse(gate.status().healthy());
        }
    }

    @Test
    void acknowledgementFailureAfterCheckpointCommitAlsoHoldsForwardProgress() {
        try (var gate = gate()) {
            var stage = gate.commit(TASK_COMPLETED, () -> CompletableFuture.completedFuture(null),
                    () -> { throw new IllegalStateException("ack store unavailable"); });
            assertFalse(stage.isDone()); assertFalse(gate.status().healthy());
            assertEquals(TASK_COMPLETED, gate.status().failure().orElseThrow().operation());
        }
    }

    @Test
    void shutdownNeverReleasesAnOutstandingOrNewCheckpoint() {
        var gate = gate(); var actual = new CompletableFuture<Void>();
        var stage = gate.commit(STARTED, () -> actual, () -> fail("closed"));
        gate.close(); actual.complete(null);
        assertFalse(stage.isDone()); assertFalse(gate.status().healthy());
        assertFalse(gate.commit(ABORTED, () -> { fail("closed"); return null; }, () -> {}).isDone());
    }

    @Test
    void rejectsUnboundedOrNonPositiveTimeouts() {
        for (var timeout : java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(6))) {
            assertThrows(IllegalArgumentException.class, () -> new PersistenceCommitGate(timeout, Clock.systemUTC()));
        }
    }
}
