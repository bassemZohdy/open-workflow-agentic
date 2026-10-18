package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

/** What an {@link Evidence} record captured. */
public enum EvidenceKind {
    /** The output of a tool call. */
    TOOL_RESULT,
    /** The output of a model call. */
    MODEL_OUTPUT,
    /** Content taken from a document or a retrieved source. */
    SOURCE_EXTRACT,
    /** A human decision recorded on a human task. */
    HUMAN_DECISION,
    /** A policy decision that an agent step relied on. */
    POLICY_DECISION
}
