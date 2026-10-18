package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

/** Lifecycle state of an agent task as stored in {@link AgentTaskRecord}. */
public enum AgentTaskStatus {
    /** Created; no step has run yet. */
    PENDING,
    /** A step is running or can run. */
    RUNNING,
    /** Paused for a human task (M05). */
    WAITING_FOR_HUMAN,
    /** Finished with its goal met. */
    COMPLETED,
    /** Finished without its goal, or stopped by a limit. */
    FAILED,
    /** Stopped by a person or by containment (M20). */
    CANCELLED
}
