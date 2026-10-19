package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import java.util.Map;

/**
 * The workflow engine as the lifecycle sees it (ADR 0019): it executes a pinned definition and nothing else. Start is
 * two steps so that the engine's instance id can be recorded before the engine runs anything. A crash between the two
 * steps leaves a registry row with a known id and no engine work, which the reconciler (WP-105c) can repair.
 *
 * <p><b>Failure semantics.</b> {@link #hasDefinition} is false for an unknown pinned version. {@link #prepare} and
 * {@link EngineLaunch#start()} throw on failure; a caller must treat a throw as "not confirmed", never as "started
 * nothing and may run again under a new id".
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
}
