package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The reconciler's and the observer's settings (WP-105c). Every value has a safe default; a test builds its own
 * instance through the constructor.
 *
 * @param reconcilerEnabled     run the startup pass and the periodic pass; off in tests that do not exercise repairs
 * @param interval              the pause between two periodic passes
 * @param warmupTimeout         how long the reconciler waits for Flow's definition warm-up before it gives up
 * @param startStaleAfter       a STARTING row is repaired only when it has not changed for this long, so that an
 *                              in-flight start in a live node is never touched
 * @param startMaxAttempts      engine start attempts before a STARTING row is marked FAILED
 * @param startBackoffBase      the wait after the first failed attempt; it doubles per attempt
 * @param cancelReissueAfter    a CANCEL_REQUESTED row is re-sent to the engine after this time without a change
 * @param waitDeadline         the wait a WAITING instance gets when the observer records the wait; the definition's
 *                              own timeout is the second guard (ADR 0019, Deadlines)
 * @param batchSize             rows read per page by the scans
 * @param crashAt              dev-only fault point; a value makes the process halt at that point (see LifecycleFaults)
 */
@Singleton
public class LifecycleSettings {

    public final boolean reconcilerEnabled;
    public final Duration interval;
    public final Duration warmupTimeout;
    public final Duration startStaleAfter;
    public final int startMaxAttempts;
    public final Duration startBackoffBase;
    public final Duration cancelReissueAfter;
    public final Duration waitDeadline;
    public final int batchSize;
    /** The longest an instance may stay RUNNING or WAITING from creation; empty means no limit (the default). */
    public final Optional<Duration> maxLifetime;
    public final Optional<String> crashAt;

    /** Without a maximum lifetime. */
    public LifecycleSettings(boolean reconcilerEnabled, Duration interval, Duration warmupTimeout,
            Duration startStaleAfter, int startMaxAttempts, Duration startBackoffBase, Duration cancelReissueAfter,
            Duration waitDeadline, int batchSize, Optional<String> crashAt) {
        this(reconcilerEnabled, interval, warmupTimeout, startStaleAfter, startMaxAttempts, startBackoffBase,
                cancelReissueAfter, waitDeadline, batchSize, crashAt, Optional.empty());
    }

    /** For the container. Keys are the ones in docs/lifecycle.md. */
    @Inject
    public LifecycleSettings(
            @ConfigProperty(name = "agentic.reconciler.enabled", defaultValue = "true") boolean reconcilerEnabled,
            @ConfigProperty(name = "agentic.reconciler.interval", defaultValue = "PT30S") Duration interval,
            @ConfigProperty(name = "agentic.reconciler.warmup-timeout", defaultValue = "PT120S") Duration warmupTimeout,
            @ConfigProperty(name = "agentic.reconciler.start-stale-after", defaultValue = "PT60S") Duration startStaleAfter,
            @ConfigProperty(name = "agentic.reconciler.start-max-attempts", defaultValue = "3") int startMaxAttempts,
            @ConfigProperty(name = "agentic.reconciler.start-backoff", defaultValue = "PT5S") Duration startBackoffBase,
            @ConfigProperty(name = "agentic.reconciler.cancel-reissue-after", defaultValue = "PT30S") Duration cancelReissueAfter,
            @ConfigProperty(name = "agentic.reconciler.wait-deadline", defaultValue = "PT24H") Duration waitDeadline,
            @ConfigProperty(name = "agentic.reconciler.batch-size", defaultValue = "100") int batchSize,
            @ConfigProperty(name = "agentic.dev.fault.crash-at") Optional<String> crashAt,
            @ConfigProperty(name = "agentic.reconciler.max-lifetime") Optional<Duration> maxLifetime) {
        this.reconcilerEnabled = reconcilerEnabled;
        this.interval = positive("agentic.reconciler.interval", interval);
        this.warmupTimeout = positive("agentic.reconciler.warmup-timeout", warmupTimeout);
        this.startStaleAfter = positive("agentic.reconciler.start-stale-after", startStaleAfter);
        if (startMaxAttempts < 1) {
            throw new IllegalArgumentException("agentic.reconciler.start-max-attempts must be at least 1");
        }
        this.startMaxAttempts = startMaxAttempts;
        this.startBackoffBase = positive("agentic.reconciler.start-backoff", startBackoffBase);
        this.cancelReissueAfter = positive("agentic.reconciler.cancel-reissue-after", cancelReissueAfter);
        this.waitDeadline = positive("agentic.reconciler.wait-deadline", waitDeadline);
        if (batchSize < 1) {
            throw new IllegalArgumentException("agentic.reconciler.batch-size must be at least 1");
        }
        this.batchSize = batchSize;
        this.crashAt = Objects.requireNonNull(crashAt, "crashAt");
        this.maxLifetime = Objects.requireNonNull(maxLifetime, "maxLifetime")
                .map(value -> positive("agentic.reconciler.max-lifetime", value));
    }

    /** The defaults of docs/lifecycle.md, with the fault point off. */
    public static LifecycleSettings defaults() {
        return new LifecycleSettings(true, Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofSeconds(60), 3,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofHours(24), 100, Optional.empty());
    }

    private static Duration positive(String key, Duration value) {
        Objects.requireNonNull(value, key);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + " must be a positive duration");
        }
        return value;
    }
}
