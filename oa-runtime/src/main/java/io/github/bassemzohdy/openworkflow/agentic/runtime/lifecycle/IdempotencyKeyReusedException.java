package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;

/**
 * The idempotency key was used before for a different request (another definition, version or input). The first
 * request's instance is not changed.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Ulid existingInstance;

    public IdempotencyKeyReusedException(Ulid existingInstance) {
        super("the idempotency key was already used for a different request");
        this.existingInstance = existingInstance;
    }

    /** The instance created by the first request with this key. */
    public Ulid existingInstance() {
        return existingInstance;
    }
}
