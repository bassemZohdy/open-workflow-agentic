package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

/**
 * Lifecycle state of an agent task as stored in {@link AgentTaskRecord}. The allowed moves are defined by the
 * runtime's state machine (WP-105, {@code docs/lifecycle.md}); this enum only names the states.
 */
public enum AgentTaskStatus {
    /** Created; no step has run yet. The only state a new row may have. */
    PENDING,
    /** A step is running or can run. */
    RUNNING,
    /** Paused for a human task (M05). The graph is interrupted at a checkpoint. */
    WAITING_FOR_HUMAN,
    /** A human decision arrived; the graph is being resumed from its checkpoint (ADR 0023, decision 7). */
    RESUMING,
    /** Finished with its goal met. */
    COMPLETED,
    /** Finished without its goal, or stopped by a limit. */
    FAILED,
    /** Stopped by a person or by containment (M20). */
    CANCELLED
}
