package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/**
 * The status of an engine instance as the engine reports it, named after Quarkus Flow's {@code WorkflowStatus}
 * (WP-105c). The registry never stores these names; {@link EngineStatusSync} maps them to {@link InstanceStatus}.
 */
public enum EngineStatus {
    /** Created or restored into memory, not started. A restored waiting instance is PENDING (WP-003, fact 1). */
    PENDING,
    /** Executing a task. */
    RUNNING,
    /** Waiting for a listen event or a timer. */
    WAITING,
    /** Finished normally. The engine deletes the instance row after this. */
    COMPLETED,
    /** Finished with an error. The engine deletes the instance row after this. */
    FAULTED,
    /** Removed by a cancel. The engine deletes the instance row after this. */
    CANCELLED,
    /** Suspended by the engine. The registry has no state for it. */
    SUSPENDED
}
