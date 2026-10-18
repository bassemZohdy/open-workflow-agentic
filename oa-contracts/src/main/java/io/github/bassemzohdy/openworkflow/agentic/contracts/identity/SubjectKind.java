package io.github.bassemzohdy.openworkflow.agentic.contracts.identity;

/** The kind of actor a {@link Subject} names. */
public enum SubjectKind {
    /** A person, identified by the identity provider. */
    USER,
    /** An agent with its own workload identity (ADR 0021). */
    AGENT,
    /** A workflow instance acting for its owner. */
    WORKFLOW,
    /** A platform service or job without a person behind it. */
    SERVICE
}
