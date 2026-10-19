package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Runs the {@link OutboxRelay} in this process, every {@code agentic.outbox.relay.interval} (WP-105b).
 *
 * <p>The schedule is a plain single-thread executor rather than db-scheduler: the relay is a poll of a table, not a
 * timer that must survive a restart, and several processes may poll at once because the claim uses SKIP LOCKED. A
 * tick drains batches until one delivers nothing, at most {@link #MAX_BATCHES_PER_TICK} of them, so a backlog clears
 * quickly without holding the thread for ever.
 *
 * <p>Consumers are every {@link OutboxConsumer} bean. Set {@code agentic.outbox.relay.enabled=false} to stop this
 * process from delivering; tests then drive their own relays with {@link OutboxRelay#drainOnce()}.
 */
@ApplicationScoped
public class OutboxRelayService {

    static final int MAX_BATCHES_PER_TICK = 50;

    private static final Logger LOG = Logger.getLogger(OutboxRelayService.class);

    private final DataSource dataSource;
    private final Instance<OutboxConsumer> consumers;
    private final boolean enabled;
    private final RelaySettings settings;

    private ScheduledExecutorService executor;
    private OutboxRelay relay;

    @Inject
    public OutboxRelayService(
            DataSource dataSource,
            Instance<OutboxConsumer> consumers,
            @ConfigProperty(name = "agentic.outbox.relay.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "agentic.outbox.relay.batch-size", defaultValue = "20") int batchSize,
            @ConfigProperty(name = "agentic.outbox.relay.max-attempts", defaultValue = "5") int maxAttempts,
            @ConfigProperty(name = "agentic.outbox.relay.backoff-base", defaultValue = "1s") Duration backoffBase,
            @ConfigProperty(name = "agentic.outbox.relay.backoff-max", defaultValue = "1m") Duration backoffMax,
            @ConfigProperty(name = "agentic.outbox.relay.interval", defaultValue = "500ms") Duration interval) {
        this.dataSource = dataSource;
        this.consumers = consumers;
        this.enabled = enabled;
        this.settings = new RelaySettings(batchSize, maxAttempts, backoffBase, backoffMax, interval);
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("outbox relay disabled in this process");
            return;
        }
        relay = new OutboxRelay(dataSource, consumers.stream().toList(), settings);
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long millis = settings.interval().toMillis();
        executor.scheduleWithFixedDelay(this::tick, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops scheduling and waits for a tick in progress to finish, so that no batch commits after the application has
     * stopped. A batch is short, so the wait is bounded by a few seconds.
     */
    void onStop(@Observes ShutdownEvent event) throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn("outbox relay did not stop within 10 seconds");
            }
        }
    }

    /** The relay of this process, or empty when the relay is disabled here. */
    public Optional<OutboxRelay> relay() {
        return Optional.ofNullable(relay);
    }

    /** Whether this process delivers outbox rows. */
    public boolean enabled() {
        return enabled;
    }

    void tick() {
        try {
            relay.drainAll(MAX_BATCHES_PER_TICK);
        } catch (RuntimeException e) {
            LOG.warnf("outbox relay tick failed: %s", e.getClass().getName());
        }
    }
}
