package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;

/**
 * The minimal port through which components write to the audit trail (M15). Policy, gateways and workflow glue call
 * this; they never touch the database.
 *
 * <p>Contract for callers:
 * <ul>
 *   <li>The caller supplies the event id, the time, the actor, the action, the resource and the attributes. Build the
 *       event with {@link AuditDraft#of} so that the placeholder hash fields are well formed.</li>
 *   <li>The trail assigns {@code sequence}, {@code previousHash} and {@code hash}; any values the caller passes for
 *       them are ignored. The time is truncated to microseconds, the precision the database stores.</li>
 *   <li>Attributes must be non-sensitive. Sensitive payloads go through {@link AuditRecorder}, which applies the
 *       retention rule first.</li>
 *   <li>The call returns after the event is committed. If it throws, the event was not recorded, and the caller must
 *       treat the decision it describes as unrecorded (fail closed where the audit is required).</li>
 * </ul>
 */
public interface AuditSink {

    /** Appends {@code event} to its chain (the chain named by its {@code oa.chain} attribute, default {@code default}). */
    void record(AuditEvent event);
}
