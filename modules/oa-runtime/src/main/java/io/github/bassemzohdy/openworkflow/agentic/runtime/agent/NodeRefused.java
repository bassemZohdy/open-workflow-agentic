package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

/**
 * Thrown when a graph node is not run, or its result is dropped, because the step limit was reached or the audit
 * record for the step could not be written (see {@link NodeInterceptor}).
 */
public final class NodeRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NodeRefused(String message) {
        super(message);
    }
}
