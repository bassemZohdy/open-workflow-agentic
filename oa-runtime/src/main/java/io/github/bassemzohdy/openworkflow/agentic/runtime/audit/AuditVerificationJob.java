package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Periodic verification of every audit chain (M15). Runs as a db-scheduler job (quarkus-db-scheduler implements the
 * Quarkus scheduler), so it runs once per schedule across several instances. The cron expression is set by
 * {@code agentic.audit.verify-cron}. A broken chain is logged at ERROR; the same check is available on demand at
 * {@code POST /audit/verify}.
 */
@ApplicationScoped
public class AuditVerificationJob {

    private static final Logger LOG = Logger.getLogger(AuditVerificationJob.class);

    @Inject
    ChainVerifier verifier;

    @Scheduled(cron = "{agentic.audit.verify-cron}", identity = "audit-chain-verification",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void verifyAllChains() {
        List<ChainReport> reports = verifier.verifyAll();
        for (ChainReport report : reports) {
            if (!report.intact()) {
                LOG.errorf("audit chain %s is broken at sequence %d: %s", report.chainId(),
                        report.firstBrokenSequence(), report.reason());
            }
        }
        LOG.debugf("audit verification checked %d chains", reports.size());
    }
}
