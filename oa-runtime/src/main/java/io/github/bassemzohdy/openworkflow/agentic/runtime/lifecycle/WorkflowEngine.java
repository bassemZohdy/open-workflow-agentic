package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workflow engine as the lifecycle sees it (ADR 0019): it executes a pinned definition and nothing else. Start is
 * two steps so that the engine's instance id can be recorded before the engine runs anything. A crash between the two
 * steps leaves a registry row with a known id and no engine work, which the reconciler (WP-105c) can repair.
 *
 * <p><b>Failure semantics.</b> {@link #hasDefinition} is false for an unknown pinned version. {@link #prepare} and
 * {@link EngineLaunch#start()} throw on failure; a caller must treat a throw as "not confirmed", never as "started
 * nothing and may run again under a new id".
 *
 * <p>The methods after {@link #prepare} serve the reconciler and the status observer (WP-105c). Their defaults answer
 * "unknown" (empty, false, none), so an implementation that does not override them is treated as an engine with no
 * instances in memory, and the reconciler then repairs nothing that needs the engine.
 */
public interface WorkflowEngine {

    /** True when this engine holds the pinned definition version. */
    boolean hasDefinition(DefinitionRef definition);

    /**
     * Creates the engine instance for the input and gives it its id. It does not start the instance.
     *
     * @throws IllegalStateException if the definition is not held
     */
    EngineLaunch prepare(DefinitionRef definition, Map<String, Object> input);

    /** A prepared engine instance: its id is fixed, and {@link #start()} starts it without waiting for completion. */
    interface EngineLaunch {

        /** The engine's id of the instance. */
        String engineInstanceId();

        /** Starts the instance. Returns when the engine has accepted it, not when it finishes. */
        void start();
    }

    /** An instance the engine holds in memory, with its status as the engine reports it. */
    record ActiveInstance(String engineInstanceId, EngineStatus status) {
    }

    /**
     * The instance with this engine id, if the engine holds it in memory. Empty when the engine does not hold it:
     * it was never restored, it finished, or it was cancelled and removed.
     */
    default Optional<ActiveInstance> activeInstance(DefinitionRef definition, String engineInstanceId) {
        return Optional.empty();
    }

    /** The ids of the instances the engine holds in memory for one pinned definition version. */
    default List<String> activeInstanceIds(DefinitionRef definition) {
        return List.of();
    }

    /** Every pinned definition version the engine holds. */
    default List<DefinitionRef> definitions() {
        return List.of();
    }

    /**
     * Starts an instance the engine holds in memory but has not started: a restored instance (PENDING) that Quarkus
     * Flow does not start by itself (WP-003, fact 1; ADR 0019, Resume). Returns false when the instance is absent or
     * is not pending, so a running instance is never started twice.
     */
    default boolean startRestored(DefinitionRef definition, String engineInstanceId) {
        return false;
    }

    /**
     * Asks the engine to cancel an instance it holds. The removal is asynchronous (WP-003, Q05): true means the engine
     * accepted the request, not that the instance is gone. The registry learns the outcome from the engine's report.
     */
    default boolean cancel(DefinitionRef definition, String engineInstanceId) {
        return false;
    }
}
