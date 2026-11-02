package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.lifecycle.PendingCancellationDatabase;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Exercise the actual scrape query and recovery against an isolated PostgreSQL database. */
@QuarkusTest
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
class OutboxMetricsDatabaseTimeoutTest {
    @Inject DataSource data;
    @Inject MeterRegistry registry;

    private double value(String name) { return registry.get(name).gauge().value(); }

    @Test void lockedOutboxTimesOutAndLaterObservationRecovers() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (value(OutboxMetrics.HEALTHY) != 1) Thread.sleep(10);
        });
        double previous = value(OutboxMetrics.UNPUBLISHED);
        assertFalse(Double.isNaN(previous));
        assertEquals(1, value(OutboxMetrics.HEALTHY));
        try (var c = data.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var lock = c.createStatement()) { lock.execute("lock table task.outbox in access exclusive mode"); }
                Thread.sleep(1100); // allow the one-second observation cache to expire
                assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                    while (value(OutboxMetrics.HEALTHY) != 0) Thread.sleep(10);
                });
                assertEquals(previous, value(OutboxMetrics.UNPUBLISHED));
            } finally { c.rollback(); }
        }
        Thread.sleep(1100);
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (value(OutboxMetrics.HEALTHY) != 1) Thread.sleep(10);
        });
        assertFalse(Double.isNaN(value(OutboxMetrics.UNPUBLISHED)));
    }
}
