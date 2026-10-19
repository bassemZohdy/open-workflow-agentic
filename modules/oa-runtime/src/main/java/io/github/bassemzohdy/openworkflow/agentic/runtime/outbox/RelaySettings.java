package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning of the outbox relay.
 *
 * @param batchSize   rows claimed per transaction; small, so a batch holds its locks briefly
 * @param maxAttempts failed deliveries before a row is marked failed (a poison row)
 * @param backoffBase delay after the first failure; doubles with each further failure
 * @param backoffMax  upper bound of the delay
 * @param interval    pause between relay runs in the scheduled mode
 */
public record RelaySettings(int batchSize, int maxAttempts, Duration backoffBase, Duration backoffMax,
                            Duration interval) {

    public static final int MAX_BATCH_SIZE = 1_000;

    public RelaySettings {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be 1 to " + MAX_BATCH_SIZE);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(backoffBase, "backoffBase");
        Objects.requireNonNull(backoffMax, "backoffMax");
        Objects.requireNonNull(interval, "interval");
        if (backoffBase.isNegative() || backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("backoff must satisfy 0 <= base <= max");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    /** Batches of 20, five attempts, backoff from one second to one minute, a run every half second. */
    public static RelaySettings defaults() {
        return new RelaySettings(20, 5, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMillis(500));
    }
}
