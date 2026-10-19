package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link AuditSink}: writes each decision to the {@code oa.policy.decision} logger, at INFO for ALLOW and
 * WARNING for DENY and ERROR. It is not an audit trail: it is not durable, not tamper-evident and not complete under
 * load. WP-111 replaces it.
 */
public final class LoggingAuditSink implements AuditSink {

    private static final Logger LOG = Logger.getLogger("oa.policy.decision");

    @Override
    public void record(PolicyDecisionRecord record) {
        AuthorizationRequest request = record.request();
        Decision decision = record.decision();
        Level level = decision.allows() ? Level.INFO : Level.WARNING;
        if (!LOG.isLoggable(level)) {
            return;
        }
        LOG.log(level, () -> String.format(
                "outcome=%s decidedBy=%s policy=%s version=%s subject=%s:%s action=%s resource=%s task=%s recovery=%s"
                        + " reason=%s",
                decision.outcome(), decision.decidedBy(), decision.policy().policyId(),
                decision.policy().policySetVersion(), request.subject().kind(), request.subject().id(),
                request.action(), request.resource(), request.taskId(), record.recoveryPath(), decision.reason()));
    }
}
