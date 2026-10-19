package io.github.bassemzohdy.openworkflow.agentic.testkit.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ControllableClockTest {

    @Test
    void doesNotMoveOnItsOwn() throws InterruptedException {
        ControllableClock clock = new ControllableClock(Instant.parse("2026-01-01T00:00:00Z"));
        Thread.sleep(20);
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), clock.instant());
    }

    @Test
    void advancesByTheGivenDuration() {
        ControllableClock clock = new ControllableClock(Instant.parse("2026-01-01T00:00:00Z"));
        clock.advance(Duration.ofHours(2).plusMillis(5));
        assertEquals(Instant.parse("2026-01-01T02:00:00.005Z"), clock.instant());
    }

    @Test
    void refusesToGoBackwardsWhenAdvanced() {
        ControllableClock clock = ControllableClock.atPlanDate();
        assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofSeconds(-1)));
    }

    @Test
    void canBeSetBackwardsExplicitly() {
        ControllableClock clock = ControllableClock.atPlanDate();
        clock.setInstant(Instant.parse("2000-01-01T00:00:00Z"));
        assertEquals(Instant.parse("2000-01-01T00:00:00Z"), clock.instant());
    }

    @Test
    void zoneViewsShareTheSameTime() {
        ControllableClock clock = ControllableClock.atPlanDate();
        Clock dubai = clock.withZone(ZoneId.of("Asia/Dubai"));
        clock.advance(Duration.ofMinutes(10));
        assertEquals(clock.instant(), dubai.instant());
        assertEquals(ZoneId.of("Asia/Dubai"), dubai.getZone());
        assertEquals(ZoneId.of("UTC"), clock.getZone());
    }
}
