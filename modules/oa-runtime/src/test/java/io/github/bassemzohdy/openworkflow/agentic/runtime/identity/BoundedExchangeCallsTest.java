package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class BoundedExchangeCallsTest {
    @Test void timedOutWorkKeepsItsSlotUntilTheWorkerActuallyStops() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        try (var calls = new BoundedExchangeCalls(1)) {
            try {
                assertThrows(TimeoutException.class, () -> calls.call(() -> {
                    entered.countDown();
                    try {
                        while (true) {
                            try { release.await(); break; } catch (InterruptedException ignored) { /* test uncooperative work */ }
                        }
                        return "late";
                    } finally { stopped.countDown(); }
                }, Duration.ofSeconds(1)));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertThrows(RejectedExecutionException.class, () -> calls.call(() -> "must not start", Duration.ofSeconds(1)));
            } finally { release.countDown(); }
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void validatesCapacityAndRefusesAfterClose() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedExchangeCalls(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedExchangeCalls(1025));
        var calls = new BoundedExchangeCalls(1);
        calls.close();
        assertThrows(RejectedExecutionException.class, () -> calls.call(() -> "x", Duration.ofSeconds(1)));
    }
}
