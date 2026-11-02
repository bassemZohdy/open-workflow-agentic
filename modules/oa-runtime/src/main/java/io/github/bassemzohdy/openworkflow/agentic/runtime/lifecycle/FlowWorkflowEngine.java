package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
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
    private static final int MAX_PENDING_STARTS = 1024;
    private static final ReferenceQueue<WorkflowInstance> START_QUEUE = new ReferenceQueue<>();
    private static final java.util.Set<StartIdentity> START_CLAIMS = new HashSet<>();

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
                if (!startPending(instance)) throw new IllegalStateException("prepared engine instance is no longer pending");
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
        return instance.isPresent() && startPending(instance.get());
    }

    @Override
    public boolean cancel(DefinitionRef definition, String engineInstanceId) {
        return definition(definition).flatMap(pinned -> pinned.activeInstance(engineInstanceId))
                .map(WorkflowInstance::cancel)
                .orElse(false);
    }

    /** Prevent concurrent starts of one SDK instance without blocking cancellation on a slow start. */
    static boolean startPending(WorkflowInstance instance) {
        java.util.Objects.requireNonNull(instance);
        var identity = new StartIdentity(instance, null);
        synchronized (START_CLAIMS) {
            StartIdentity collected;
            while ((collected = (StartIdentity) START_QUEUE.poll()) != null) START_CLAIMS.remove(collected);
            if (START_CLAIMS.contains(identity) || START_CLAIMS.size() >= MAX_PENDING_STARTS) return false;
            START_CLAIMS.add(new StartIdentity(instance, START_QUEUE));
        }
        boolean invoked = false;
        try {
            if (instance.status() != WorkflowStatus.PENDING) return false;
            invoked = true;
            instance.start();
            return true;
        } finally {
            boolean release = !invoked;
            // A thrown or asynchronous start may have entered work without changing status. Do not retry it blindly.
            if (invoked) try { release = instance.status() != WorkflowStatus.PENDING; }
            catch (RuntimeException unavailable) { /* Retain the weak claim while status is unknown. */ }
            if (release) synchronized (START_CLAIMS) { START_CLAIMS.remove(identity); }
        }
    }

    private static final class StartIdentity extends WeakReference<WorkflowInstance> {
        private final int hash;
        StartIdentity(WorkflowInstance instance, ReferenceQueue<WorkflowInstance> queue) {
            super(instance, queue); hash = System.identityHashCode(instance);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StartIdentity identity)) return false;
            WorkflowInstance value = get(); return value != null && value == identity.get();
        }
    }

    private static EngineStatus status(WorkflowInstance instance) {
        return EngineStatus.valueOf(instance.status().name());
    }

    private Optional<WorkflowDefinition> definition(DefinitionRef definition) {
        return Optional.ofNullable(application.workflowDefinitions().get(
                new WorkflowDefinitionId(definition.namespace(), definition.name(), definition.version())));
    }
}
