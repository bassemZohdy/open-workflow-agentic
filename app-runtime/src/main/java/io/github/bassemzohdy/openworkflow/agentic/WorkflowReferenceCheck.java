package io.github.bassemzohdy.openworkflow.agentic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.WorkflowReferenceValidator;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** Validates workflow reference manifests against loaded definitions and registered runtime capabilities at startup. */
@Startup
@ApplicationScoped
public class WorkflowReferenceCheck {
    @Inject ObjectMapper mapper;
    private static final Set<String> LOADED_WORKFLOWS = Set.of("research-and-approve");

    @PostConstruct
    void validateReferences() {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("workflow-references.json")) {
            if (input == null) throw new IllegalStateException("Missing workflow-references.json");
            var references = mapper.readValue(input,
                    new TypeReference<java.util.List<WorkflowReferenceValidator.References>>() {});
            WorkflowReferenceValidator.validate(references, LOADED_WORKFLOWS,
                    Set.of("research"), Set.of(), Set.of());
        } catch (IOException e) {
            throw new IllegalStateException("Could not load workflow reference manifest", e);
        }
    }
}
