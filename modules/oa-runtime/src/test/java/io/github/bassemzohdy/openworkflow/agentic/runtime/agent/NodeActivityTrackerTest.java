package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bsc.langgraph4j.action.NodeAction;
import org.junit.jupiter.api.Test;

class NodeActivityTrackerTest {
    @Test void emitterRemainsTrackedUntilItsPhysicalWorkFinishes() throws Exception {
        var tracker = new NodeActivityTracker();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var closed = new CountDownLatch(1);
        var executor = tracker.emitterExecutor();
        executor.execute(() -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            var closer = Thread.ofVirtual().start(() -> { tracker.closeAndAwait(); closed.countDown(); });
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS));
            release.countDown(); assertTrue(closed.await(5, TimeUnit.SECONDS)); closer.join(5000);
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        } finally { release.countDown(); }
    }

    @Test void closingWaitsForPhysicalNodeExitAndPreservesInterrupt() throws Exception {
        var tracker = new NodeActivityTracker();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1); var closed = new CountDownLatch(1);
        var restored = new AtomicBoolean();
        NodeAction<ResearchState> action = tracker.track(state -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return Map.of();
        });
        var worker = Thread.ofVirtual().start(() -> {
            try { action.apply(new ResearchState(Map.of())); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var closer = Thread.ofVirtual().start(() -> {
            closeStarted.countDown(); tracker.closeAndAwait();
            restored.set(Thread.currentThread().isInterrupted()); closed.countDown();
        });
        try {
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS)); closer.interrupt();
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS), "interrupt must not release an active node");
            release.countDown(); assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(restored.get());
            assertThrows(ModelCallRefused.class, () -> tracker.track((ResearchState s) -> Map.of()).apply(new ResearchState(Map.of())));
        } finally { release.countDown(); worker.join(5000); closer.join(5000); }
    }
}
