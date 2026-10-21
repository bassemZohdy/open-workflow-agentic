package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

/**
 * The same retained audit source (or the same event id) was presented with different content, id, chain or source
 * tuple. It is a conflict, never another event: nothing was appended and nothing about the stored event is disclosed.
 */
public final class AuditSourceConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditSourceConflictException(String message) {
        super(message);
    }
}
