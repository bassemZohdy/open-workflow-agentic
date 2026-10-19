package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Objects;
import org.jboss.logging.Logger;

/**
 * Micrometer gauges of the outbox lag (WP-105b): unpublished row count, age of the oldest unpublished row, and failed
 * row count. The database is read at most once per second, however often the registry is scraped. When the database
 * cannot be read the gauges keep their last value and the failure is logged, so a scrape never fails.
 */
@ApplicationScoped
public class OutboxMetrics {

    public static final String UNPUBLISHED = "agentic.outbox.unpublished";
    public static final String OLDEST_AGE = "agentic.outbox.unpublished.oldest.age.seconds";
    public static final String FAILED = "agentic.outbox.failed";

    private static final Duration CACHE = Duration.ofSeconds(1);
    private static final Logger LOG = Logger.getLogger(OutboxMetrics.class);

    private final OutboxAdmin admin;
    private final MeterRegistry registry;
    private volatile OutboxAdmin.Lag last = new OutboxAdmin.Lag(0, 0, 0);
    private volatile long lastReadNanos;

    @Inject
    public OutboxMetrics(OutboxAdmin admin, MeterRegistry registry) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    void onStart(@Observes StartupEvent event) {
        Gauge.builder(UNPUBLISHED, this, m -> m.current().unpublished())
                .description("Outbox rows not yet published, failed ones included").register(registry);
        Gauge.builder(OLDEST_AGE, this, m -> m.current().oldestUnpublishedAgeSeconds())
                .description("Age of the oldest unpublished outbox row").baseUnit("seconds").register(registry);
        Gauge.builder(FAILED, this, m -> m.current().failed())
                .description("Outbox rows marked failed (poison rows)").register(registry);
    }

    /** The lag as of at most one second ago. */
    OutboxAdmin.Lag current() {
        long now = System.nanoTime();
        if (now - lastReadNanos < CACHE.toNanos() && lastReadNanos != 0) {
            return last;
        }
        try {
            last = admin.lag();
            lastReadNanos = now;
        } catch (RuntimeException e) {
            LOG.warnf("outbox lag could not be read: %s", e.getClass().getName());
        }
        return last;
    }
}
