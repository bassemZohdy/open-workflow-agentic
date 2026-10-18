package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

/** The kind of action an {@link AuthorizationRequest} asks about. */
public enum ActionKind {
    /** An agent calls a tool through the tool gateway. */
    TOOL_CALL,
    /** An agent calls a model through the model gateway. */
    MODEL_CALL,
    /** Data is read or written under a data scope. */
    DATA_ACCESS,
    /** An external side effect is committed through the effect ledger. */
    EFFECT_COMMIT,
    /** A paused agent or workflow resumes under a delegation grant. */
    RESUME,
    /** Authority is delegated to another agent or subject. */
    DELEGATE,
    /** A person acts on a human task (approve, redirect, take over). */
    HUMAN_TASK_ACTION,
    /** A versioned artifact is published or activated for execution. */
    PUBLICATION,
    /** An operator runs a safe-recovery operation (cancel, pause, drain) on work or on the runtime. */
    OPERATION
}
