package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Periodic sweep that marks delegation grants whose expiry has passed as EXPIRED and writes the audit event. It runs as
 * a db-scheduler job (one run per schedule across instances). A grant is unusable from its expiry whether or not the
 * sweep has run, so a late or failed sweep never extends authority.
 */
@ApplicationScoped
public class GrantExpiryJob {

    private static final Logger LOG = Logger.getLogger(GrantExpiryJob.class);

    @Inject
    DelegationGrants grants;

    @Scheduled(cron = "{agentic.identity.expiry-sweep-cron}", identity = "identity-grant-expiry",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        try {
            int marked = grants.expireDue();
            LOG.debugf("grant expiry sweep marked %d grants", marked);
        } catch (RuntimeException e) {
            LOG.warnf(e, "grant expiry sweep failed; expired grants stay unusable and are marked on the next run");
        }
    }
}
