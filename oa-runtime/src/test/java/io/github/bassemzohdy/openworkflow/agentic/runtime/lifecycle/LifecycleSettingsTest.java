package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LifecycleSettingsTest {

    @Test
    void theDefaultsAreTheOnesDocumented() {
        LifecycleSettings settings = LifecycleSettings.defaults();
        assertEquals(Duration.ofSeconds(30), settings.interval);
        assertEquals(3, settings.startMaxAttempts);
        assertEquals(Duration.ofHours(24), settings.waitDeadline);
        assertFalse(settings.crashAt.isPresent());
    }

    @Test
    void aNonPositiveDurationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LifecycleSettings(true, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1, Optional.empty()));
    }

    @Test
    void zeroAttemptsAndAZeroBatchAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LifecycleSettings(true, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleSettings(true, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 0, Optional.empty()));
    }

    @Test
    void theFaultHookDoesNothingWhenNoPointIsConfigured() {
        LifecycleFaults.none().hit(LifecycleFaults.AFTER_INSERT);
        LifecycleFaults.none().hit(LifecycleFaults.AFTER_PREPARE);
    }
}
