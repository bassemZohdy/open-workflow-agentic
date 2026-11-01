package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Periodic two-stage retention of the event inbox. Off unless {@code agentic.inbox.retention-sweep-cron} is set to a
 * cron expression. Stage one redacts the payload of expired, unreferenced messages shortly after their retention ended
 * and keeps the row as a tombstone, so duplicate and conflicting redeliveries are still recognised. Stage two deletes
 * the tombstone after {@code agentic.inbox.tombstone-retention} (default 90 days), which is when the duplicate-receipt
 * window finally ends. It runs as a db-scheduler job (one run per schedule across replicas) in bounded batches, so a
 * late or failed sweep only delays space reclamation.
 */
@ApplicationScoped
public class InboxRetentionJob {

    private static final Logger LOG = Logger.getLogger(InboxRetentionJob.class);
    static final int BATCH = 500;
    static final int MAX_BATCHES = 20;
    static final Duration GRACE = Duration.ofMinutes(5);

    @Inject
    JdbcEventInbox inbox;

    @ConfigProperty(name = "agentic.inbox.tombstone-retention", defaultValue = "P90D")
    Duration tombstoneRetention;

    @Scheduled(cron = "{agentic.inbox.retention-sweep-cron:off}", identity = "inbox-retention",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        try {
            int[] done = sweepOnce();
            LOG.debugf("inbox retention sweep redacted %d payloads and deleted %d tombstones", done[0], done[1]);
        } catch (RuntimeException e) {
            LOG.warnf(e, "inbox retention sweep failed; expired messages stay unmatchable and are handled by a later run");
        }
    }

    /** Runs both stages in bounded batches; returns {redacted, deleted}. */
    int[] sweepOnce() {
        if (tombstoneRetention == null || tombstoneRetention.compareTo(GRACE) <= 0) {
            throw new IllegalStateException("agentic.inbox.tombstone-retention must be longer than " + GRACE);
        }
        int redacted = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = inbox.redactExpired(GRACE, BATCH);
            redacted += n;
            if (n < BATCH) break;
        }
        int deleted = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = inbox.purgeExpired(tombstoneRetention, BATCH);
            deleted += n;
            if (n < BATCH) break;
        }
        return new int[]{redacted, deleted};
    }
}
