package io.github.bassemzohdy.openworkflow.agentic.contracts.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.Objects;

/**
 * Who or what acted: a person, an agent, a workflow or a service. The id is the identifier in the identity provider
 * or the platform registry, as text.
 *
 * @param kind the kind of actor
 * @param id   the actor's identifier, non-blank, at most {@link #MAX_ID_LENGTH} characters
 */
public record Subject(SubjectKind kind, String id) {

    public static final int MAX_ID_LENGTH = 256;

    public Subject {
        Objects.requireNonNull(kind, "kind");
        id = Checks.requireText("subject id", id, MAX_ID_LENGTH);
    }
}
