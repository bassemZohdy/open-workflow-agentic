package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Micrometer gauges of the outbox lag (WP-105b): unpublished row count, age of the oldest unpublished row, and failed
 * row count. The database is read at most once per second, however often the registry is scraped. When the database
 * cannot be read the lag gauges keep their last successful value, accompanied by unhealthy and age signals.
 * Until the first success, lag and age are NaN rather than a fabricated empty queue. Failed attempts are cached too.
 */
@ApplicationScoped
public class OutboxMetrics {

    public static final String UNPUBLISHED = "agentic.outbox.unpublished";
    public static final String OLDEST_AGE = "agentic.outbox.unpublished.oldest.age.seconds";
    public static final String FAILED = "agentic.outbox.failed";
    public static final String HEALTHY = "agentic.outbox.observation.healthy";
    public static final String AGE = "agentic.outbox.observation.age.seconds";

    private static final Duration CACHE = Duration.ofSeconds(1);
    private static final Logger LOG = Logger.getLogger(OutboxMetrics.class);

    private final Supplier<OutboxAdmin.Lag> read;
    private final MeterRegistry registry;
    private final LongSupplier nanos;
    private final AtomicBoolean reading = new AtomicBoolean();
    private record Observation(OutboxAdmin.Lag lag, long attemptedAt, long succeededAt,
                               boolean attempted, boolean succeeded, boolean healthy) {}
    private volatile Observation last = new Observation(new OutboxAdmin.Lag(0,0,0),0,0,false,false,false);

    @Inject
    public OutboxMetrics(OutboxAdmin admin, MeterRegistry registry) {
        this(Objects.requireNonNull(admin, "admin")::lag, registry, System::nanoTime);
    }

    OutboxMetrics(Supplier<OutboxAdmin.Lag> read, MeterRegistry registry, LongSupplier nanos) {
        this.read = Objects.requireNonNull(read, "read");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    void onStart(@Observes StartupEvent event) {
        Gauge.builder(UNPUBLISHED, this, m -> {var o=m.current();return o.succeeded()?o.lag().unpublished():Double.NaN;})
                .description("Outbox rows not yet published, failed ones included").register(registry);
        Gauge.builder(OLDEST_AGE, this, m -> {var o=m.current();return o.succeeded()?o.lag().oldestUnpublishedAgeSeconds():Double.NaN;})
                .description("Age of the oldest unpublished outbox row").baseUnit("seconds").register(registry);
        Gauge.builder(FAILED, this, m -> {var o=m.current();return o.succeeded()?o.lag().failed():Double.NaN;})
                .description("Outbox rows marked failed (poison rows)").register(registry);
        Gauge.builder(HEALTHY, this, m -> m.current().healthy()?1:0)
                .description("Whether the latest completed outbox observation succeeded; 0 before any success").register(registry);
        Gauge.builder(AGE, this, m -> {var o=m.current();return o.succeeded()?Math.max(0,m.nanos.getAsLong()-o.succeededAt())/1e9:Double.NaN;})
                .description("Monotonic age of the last successful outbox observation; NaN until first success")
                .baseUnit("seconds").register(registry);
    }

    /** One shared observation; cache limits attempt frequency, not query duration or maximum data age. */
    private Observation current() {
        var observed=last;
        if (cached(observed) || !reading.compareAndSet(false,true)) return observed;
        try {
            observed=last;
            if (cached(observed)) return observed;
            var lag=Objects.requireNonNull(read.get(),"lag");
            long completed=nanos.getAsLong();
            last=new Observation(lag,completed,completed,true,true,true);
        } catch (RuntimeException e) {
            last=new Observation(observed.lag(),nanos.getAsLong(),observed.succeededAt(),true,observed.succeeded(),false);
            LOG.warnf("outbox lag could not be read: %s", e.getClass().getName());
        } finally {reading.set(false);}
        return last;
    }

    private boolean cached(Observation observed) {
        return observed.attempted() && nanos.getAsLong()-observed.attemptedAt()<CACHE.toNanos();
    }
}
