package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/**
 * Lifecycle status of a workflow instance in the platform registry (ADR 0019). The allowed moves are in
 * {@link InstanceStateMachine}; {@code docs/lifecycle.md} explains each state.
 */
public enum InstanceStatus {
    /** Registered; the engine start has not been confirmed yet. Rows here may be repaired by the reconciler. */
    STARTING,
    /** The engine accepted the start and the instance is executing or can execute. */
    RUNNING,
    /** The instance waits for an event or a human decision (its listen is registered). */
    WAITING,
    /** A cancel was requested; the engine has not confirmed the removal yet (ADR 0019, Cancel). */
    CANCEL_REQUESTED,
    /** Cancelled and confirmed by the engine. Terminal. */
    CANCELLED,
    /** Finished normally. Terminal. */
    COMPLETED,
    /** Finished with an error, or could not be started. Terminal. */
    FAILED;

    /** True for the states that no further transition leaves. */
    public boolean isTerminal() {
        return this == CANCELLED || this == COMPLETED || this == FAILED;
    }
}
