package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditReceipt;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditSource;

/**
 * Trusted central projection boundary. Atomically commit event, head and source binding, or read back the SAME
 * already committed receipt after checking its retained source, draft content and stored chain facts. Changed
 * content/id/actor/chain for one source conflicts. Use the shared canonical draft helper, never a new draft on retry.
 * A thrown call can have committed: retain the original source/id/time/content for subsequent readback.
 * The returned receipt is historical DATA, not permission, chain authenticity by itself, or current authority.
 * Implementations must bound dependency calls and fail closed on missing, unavailable or inconsistent storage.
 */
@FunctionalInterface
public interface IdempotentAuditSink {
    AuditReceipt appendOnce(AuditSource source, AuditEvent retainedDraft);
}
