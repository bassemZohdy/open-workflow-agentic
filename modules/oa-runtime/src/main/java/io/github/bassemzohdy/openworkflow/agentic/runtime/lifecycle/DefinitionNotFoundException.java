package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/** The engine does not hold the pinned definition version. No registry row is created for such a request. */
public final class DefinitionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DefinitionNotFoundException(DefinitionRef definition) {
        super("definition " + definition.definitionId() + " version " + definition.version() + " is not available");
    }
}
