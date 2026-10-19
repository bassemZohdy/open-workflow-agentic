package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.regex.Pattern;

/**
 * A pinned workflow definition: {@code definitionId} is {@code namespace/name}, and {@code version} is the version of
 * the definition document. The registry stores both, and a start always uses the version named in the row (ADR 0019).
 */
public record DefinitionRef(String definitionId, String version) {

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}/[a-z0-9][a-z0-9._-]{0,127}");
    static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");

    public DefinitionRef {
        definitionId = Checks.requireMatching("definitionId", definitionId, ID);
        version = Checks.requireMatching("definitionVersion", version, VERSION);
    }

    /** The namespace part of the id, as the workflow engine names it. */
    public String namespace() {
        return definitionId.substring(0, definitionId.indexOf('/'));
    }

    /** The name part of the id, as the workflow engine names it. */
    public String name() {
        return definitionId.substring(definitionId.indexOf('/') + 1);
    }
}
