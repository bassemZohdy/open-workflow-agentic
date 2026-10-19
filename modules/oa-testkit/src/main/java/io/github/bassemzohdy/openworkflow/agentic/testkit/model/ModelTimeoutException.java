package io.github.bassemzohdy.openworkflow.agentic.testkit.model;

/** A scripted model call that did not answer in time. */
public class ModelTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModelTimeoutException(String message) {
        super(message);
    }
}
