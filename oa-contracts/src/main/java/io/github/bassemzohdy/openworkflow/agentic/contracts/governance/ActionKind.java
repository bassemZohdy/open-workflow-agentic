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
    DELEGATE
}
