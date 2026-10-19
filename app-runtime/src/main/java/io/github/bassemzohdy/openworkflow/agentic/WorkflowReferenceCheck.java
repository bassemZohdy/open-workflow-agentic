package io.github.bassemzohdy.openworkflow.agentic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.WorkflowReferenceValidator;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates workflow reference manifests against loaded definitions and registered runtime capabilities at startup. */
@Startup
@ApplicationScoped
public class WorkflowReferenceCheck {
    @Inject ObjectMapper mapper;
    @Inject Instance<WorkflowDefinition> definitions;

    @PostConstruct
    void validateReferences() {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("flow/references.json")) {
            if (input == null) throw new IllegalStateException("Missing flow/references.json");
            var references = mapper.readValue(input,
                    new TypeReference<java.util.List<WorkflowReferenceValidator.References>>() {});
            var workflowNames = definitions.stream()
                    .map(d -> d.workflow().getDocument().getName()).collect(Collectors.toSet());
            WorkflowReferenceValidator.validate(references, workflowNames,
                    Set.of("research"), Set.of(), Set.of());
        } catch (IOException e) {
            throw new IllegalStateException("Could not load workflow reference manifest", e);
        }
    }
}
