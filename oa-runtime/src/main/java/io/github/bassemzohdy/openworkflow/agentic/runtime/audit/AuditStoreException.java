package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

/**
 * The audit store could not complete an operation (connection, constraint, serialisation). Callers must treat the
 * event as not recorded; the audit is never silently skipped.
 */
public class AuditStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
