package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

/**
 * The commit reply was lost and a same-source readback could not confirm the event. The event may or may not be
 * stored; this is neither absence nor permission. Repeating {@link AuditAppendOnce#appendOnce} with the same source and
 * draft is safe: it returns the original receipt if the commit happened and appends exactly once if it did not.
 */
public final class AuditCommitUncertainException extends AuditStoreException {

    private static final long serialVersionUID = 1L;

    public AuditCommitUncertainException(String message, Throwable cause) {
        super(message, cause);
    }
}
