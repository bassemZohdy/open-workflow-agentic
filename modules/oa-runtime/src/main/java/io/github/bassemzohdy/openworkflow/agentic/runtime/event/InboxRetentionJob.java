package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.jboss.logging.Logger;

/**
 * Periodic sweep that deletes expired, unreferenced inbox messages. Off unless {@code agentic.inbox.retention-sweep-cron}
 * is set to a cron expression, so enabling it is a deliberate choice about the duplicate-receipt window (an event id
 * redelivered after its message was purged is a new receipt). It runs as a db-scheduler job (one run per schedule across
 * replicas) and does bounded batches, so a late or failed sweep only delays space reclamation.
 */
@ApplicationScoped
public class InboxRetentionJob {

    private static final Logger LOG = Logger.getLogger(InboxRetentionJob.class);
    static final int BATCH = 500;
    static final int MAX_BATCHES = 20;
    static final Duration GRACE = Duration.ofMinutes(5);

    @Inject
    JdbcEventInbox inbox;

    @Scheduled(cron = "{agentic.inbox.retention-sweep-cron:off}", identity = "inbox-retention",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        try {
            int total = sweepOnce();
            LOG.debugf("inbox retention sweep deleted %d expired messages", total);
        } catch (RuntimeException e) {
            LOG.warnf(e, "inbox retention sweep failed; expired messages stay unmatchable and are deleted by a later run");
        }
    }

    /** Runs bounded batches until one deletes fewer than a full batch; returns the total deleted. */
    int sweepOnce() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = inbox.purgeExpired(GRACE, BATCH);
            total += deleted;
            if (deleted < BATCH) break;
        }
        return total;
    }
}
