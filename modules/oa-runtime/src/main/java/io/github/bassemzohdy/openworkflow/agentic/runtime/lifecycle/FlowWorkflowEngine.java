package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The Quarkus Flow adapter of {@link WorkflowEngine}. It executes the pinned definition with
 * {@code definition.instance(input)} and {@code start()}, as spike WP-003 used them. Flow ships no instance API of its
 * own, so start, query, resume and cancel are ours (ADR 0019); this class is the only place that calls Flow for them.
 */
@ApplicationScoped
public class FlowWorkflowEngine implements WorkflowEngine {

    private static final Logger LOG = Logger.getLogger(FlowWorkflowEngine.class);

    @Inject
    WorkflowApplication application;

    public FlowWorkflowEngine() { }
    /** Explicit application binding for isolated runtimes; CDI keeps the existing injected application. */
    public FlowWorkflowEngine(WorkflowApplication application) { this.application=java.util.Objects.requireNonNull(application); }

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

            @Override
            public boolean discard() {
                // prepare() registers a PENDING Flow instance. If the registry write loses a cancellation race,
                // cancel that still-pending instance so it is not left unregistered in Flow.
                return instance.cancel();
            }
        };
    }

    @Override
    public Optional<ActiveInstance> activeInstance(DefinitionRef definition, String engineInstanceId) {
        return definition(definition)
                .flatMap(pinned -> pinned.activeInstance(engineInstanceId))
                .map(instance -> new ActiveInstance(engineInstanceId, status(instance)));
    }

    @Override
    public List<String> activeInstanceIds(DefinitionRef definition) {
        return definition(definition)
                .map(pinned -> pinned.activeInstances().stream().map(instance -> String.valueOf(instance.id())).toList())
                .orElse(List.of());
    }

    @Override
    public List<DefinitionRef> definitions() {
        List<DefinitionRef> refs = new java.util.ArrayList<>();
        for (WorkflowDefinition pinned : application.workflowDefinitions().values()) {
            WorkflowDefinitionId id = pinned.id();
            try {
                refs.add(new DefinitionRef(id.namespace() + "/" + id.name(), id.version()));
            } catch (IllegalArgumentException notAPlatformDefinition) {
                // Definitions outside the platform's naming rule are not held by the lifecycle.
                LOG.debugf("definition %s is outside the lifecycle naming rule", id);
            }
        }
        return List.copyOf(refs);
    }

    @Override
    public boolean startRestored(DefinitionRef definition, String engineInstanceId) {
        Optional<WorkflowInstance> instance = definition(definition).flatMap(pinned -> pinned.activeInstance(engineInstanceId));
        if (instance.isEmpty() || instance.get().status() != WorkflowStatus.PENDING) {
            return false;
        }
        // Restored instances are registered as active but not started (WP-003, Q02). start() resumes them.
        instance.get().start();
        return true;
    }

    @Override
    public boolean cancel(DefinitionRef definition, String engineInstanceId) {
        return definition(definition).flatMap(pinned -> pinned.activeInstance(engineInstanceId))
                .map(WorkflowInstance::cancel)
                .orElse(false);
    }

    private static EngineStatus status(WorkflowInstance instance) {
        return EngineStatus.valueOf(instance.status().name());
    }

    private Optional<WorkflowDefinition> definition(DefinitionRef definition) {
        return Optional.ofNullable(application.workflowDefinitions().get(
                new WorkflowDefinitionId(definition.namespace(), definition.name(), definition.version())));
    }
}
