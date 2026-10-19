package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;

/**
 * A retry of a FAILED instance was refused because that instance already has a retry that is not terminal (ADR 0019,
 * amendment 3, one live retry per failed instance). The store raises it when the unique index of live retries rejects
 * the insert, so two concurrent retries cannot both be created.
 */
public final class LiveRetryExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Ulid failedId;

    public LiveRetryExistsException(Ulid failedId) {
        super("instance " + failedId.value() + " already has a retry that is not terminal");
        this.failedId = failedId;
    }

    /** The FAILED instance whose retry was refused. */
    public Ulid failedId() {
        return failedId;
    }
}
