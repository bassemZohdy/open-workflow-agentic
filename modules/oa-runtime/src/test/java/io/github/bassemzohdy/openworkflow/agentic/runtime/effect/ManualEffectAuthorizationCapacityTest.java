package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManualEffectAuthorizationCapacityTest {
    @Test void timedOutChecksHoldCapacityUntilTheirWorkersActuallyExit() throws Exception {
        var checks = new ManualEffectResolver.BoundedChecks(2, 100, TimeUnit.MILLISECONDS);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(2);
        var entered = new AtomicInteger();
        Callable<Void> ignoresInterrupt = () -> {
            entered.incrementAndGet();
            try {
                while (true) {
                    try { release.await(); return null; }
                    catch (InterruptedException ignored) { /* Simulates an uncooperative external dependency. */ }
                }
            } finally { exited.countDown(); }
        };
        try {
            assertThrows(IllegalStateException.class, () -> checks.call(ignoresInterrupt));
            assertThrows(IllegalStateException.class, () -> checks.call(ignoresInterrupt));
            assertEquals(2, entered.get());
            var third = new AtomicInteger();
            var refused = assertThrows(IllegalStateException.class, () -> checks.call(() -> { third.incrementAndGet(); return null; }));
            assertTrue(refused.getMessage().contains("capacity"));
            assertEquals(0, third.get());
        } finally { release.countDown(); }
        assertTrue(exited.await(5, TimeUnit.SECONDS));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (true) {
                try { assertEquals("ok", checks.call(() -> "ok")); break; }
                catch (IllegalStateException stillReleasing) { Thread.yield(); }
            }
        });
    }

    @Test void originalAuthorizationFailureIsPreserved() {
        var checks = new ManualEffectResolver.BoundedChecks(1, 1, TimeUnit.SECONDS);
        var failure = new IllegalArgumentException("refused");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> checks.call(() -> { throw failure; })));
    }
}
