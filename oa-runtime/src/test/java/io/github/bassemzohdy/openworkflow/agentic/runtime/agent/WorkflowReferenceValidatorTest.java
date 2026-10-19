package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowReferenceValidatorTest {
    @Test
    void acceptsKnownReferencesAndRejectsMissingSymbols() {
        var valid = new WorkflowReferenceValidator.References("case-flow", Set.of("research"),
                Set.of("case-reader"), Set.of("policy:case-read"));
        assertDoesNotThrow(() -> WorkflowReferenceValidator.validate(List.of(valid), Set.of("case-flow"),
                Set.of("research"), Set.of("case-reader"), Set.of("policy:case-read")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowReferenceValidator.validate(List.of(valid),
                Set.of("case-flow"), Set.of(), Set.of("case-reader"), Set.of("policy:case-read")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowReferenceValidator.validate(List.of(valid),
                Set.of("case-flow"), Set.of("research"), Set.of(), Set.of("policy:case-read")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowReferenceValidator.validate(List.of(valid),
                Set.of("case-flow"), Set.of("research"), Set.of("case-reader"), Set.of()));
    }

    @Test
    void rejectsUnknownWorkflowsAndDuplicateManifests() {
        var ref = new WorkflowReferenceValidator.References("case-flow", Set.of(), Set.of(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> WorkflowReferenceValidator.validate(List.of(ref),
                Set.of(), Set.of(), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> WorkflowReferenceValidator.validate(List.of(ref, ref),
                Set.of("case-flow"), Set.of(), Set.of(), Set.of()));
    }
}
