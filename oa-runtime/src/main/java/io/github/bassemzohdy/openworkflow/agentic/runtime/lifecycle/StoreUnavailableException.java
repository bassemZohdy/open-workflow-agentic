package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/**
 * The registry database failed. Callers must not read it as "no such row": the operation did not happen or its
 * outcome is unknown, and the reconciler resolves it from the stored state.
 */
public final class StoreUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
