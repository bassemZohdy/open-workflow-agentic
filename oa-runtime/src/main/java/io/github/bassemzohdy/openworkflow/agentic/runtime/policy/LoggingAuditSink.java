package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Logging implementation of the shared audit port for local development and tests. */
public final class LoggingAuditSink implements AuditSink {

    private static final Logger LOG = Logger.getLogger("oa.policy.decision");

    @Override
    public void record(AuditEvent event) {
        Level level = "ALLOW".equals(event.attributes().get("policy.outcome")) ? Level.INFO : Level.WARNING;
        if (!LOG.isLoggable(level)) {
            return;
        }
        LOG.log(level, () -> String.format(
                "action=%s actor=%s:%s resourceType=%s resource=%s attributes=%s",
                event.action(), event.actor().kind(), event.actor().id(), event.resourceType(), event.resourceId(),
                event.attributes()));
    }
}
