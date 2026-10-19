package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import io.serverlessworkflow.impl.WorkflowInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;

/**
 * The Quarkus Flow adapter of {@link WorkflowEngine}. It executes the pinned definition with
 * {@code definition.instance(input)} and {@code start()}, as spike WP-003 used them. Flow ships no instance API of its
 * own, so start, query and cancel are ours (ADR 0019); this class is the only place that calls Flow for them.
 */
@ApplicationScoped
public class FlowWorkflowEngine implements WorkflowEngine {

    @Inject
    WorkflowApplication application;

    @Override
    public boolean hasDefinition(DefinitionRef definition) {
        return definition(definition).isPresent();
    }

    @Override
    public EngineLaunch prepare(DefinitionRef definition, Map<String, Object> input) {
        WorkflowDefinition pinned = definition(definition).orElseThrow(
                () -> new IllegalStateException("definition is not held by the engine"));
        WorkflowInstance instance = pinned.instance(input);
        String engineInstanceId = String.valueOf(instance.id());
        return new EngineLaunch() {
            @Override
            public String engineInstanceId() {
                return engineInstanceId;
            }

            @Override
            public void start() {
                // start() returns a future that completes when the instance completes or waits. We do not wait for it.
                instance.start();
            }
        };
    }

    private Optional<WorkflowDefinition> definition(DefinitionRef definition) {
        return Optional.ofNullable(application.workflowDefinitions().get(
                new WorkflowDefinitionId(definition.namespace(), definition.name(), definition.version())));
    }
}
