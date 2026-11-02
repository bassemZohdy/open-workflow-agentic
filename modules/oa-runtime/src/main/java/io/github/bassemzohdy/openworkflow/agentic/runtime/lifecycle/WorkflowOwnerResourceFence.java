package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import io.serverlessworkflow.impl.WorkflowContextData;
import java.util.Objects;

/**
 * Explicit candidate for the owner-and-lease portion of a single resource execution. The trusted dispatcher must
 * supply the actual SDK context and the exact durable entry, never data supplied by a workflow or request.
 * It does not mint an entry or check identity, policy, task, target or audit, and is not installed by default.
 * A successful check cannot fence an external call already in flight. Close after actual execution terminates.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class WorkflowOwnerResourceFence implements ResourceExecutionFence, AutoCloseable {
    private final ResourceExecutionEntry entry;
    private final WorkflowOwnerContextBindings bindings;
    private final InstanceRegistryFence registry;
    private final WorkflowOwnerExecutionFence owner;
    private volatile WorkflowContextData context;

    public WorkflowOwnerResourceFence(ResourceExecutionEntry entry, WorkflowContextData context,
                                      WorkflowOwnerContextBindings bindings, InstanceRegistryFence registry,
                                      WorkflowOwnerExecutionFence owner) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.context = Objects.requireNonNull(context, "context");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override public void requireCurrent(ResourceExecutionEntry requested) {
        if (Thread.currentThread().isInterrupted()) throw refused("resource owner check interrupted");
        if (!entry.equals(requested)) throw refused("resource entry differs");
        var currentContext = context;
        if (currentContext == null) throw refused("resource owner context is closed");
        try {
            var claim = bindings.require(currentContext);
            var binding = claim.binding();
            var scope = entry.scope();
            if (!binding.tenant().equals(scope.tenant()) || !binding.instanceId().equals(scope.instanceId())
                    || !binding.applicationId().equals(scope.applicationId())
                    || !binding.engineInstanceId().equals(scope.engineInstanceId())
                    || !binding.definition().definitionId().equals(scope.namespace() + "/" + scope.definition())
                    || !binding.definition().version().equals(scope.version()))
                throw refused("resource owner binding differs");
            owner.requireCurrent(currentContext);
            registry.requireCurrent(entry);
            // Registry I/O can outlast the owner lease or race local revocation.
            owner.requireCurrent(currentContext);
            if (context != currentContext) throw refused("resource owner context is closed");
            if (Thread.currentThread().isInterrupted()) throw refused("resource owner check interrupted");
        } catch (JdbcResourceExecutionGate.RefusedException refused) {
            throw refused;
        } catch (RuntimeException failure) {
            var refused = refused("resource owner check unavailable");
            refused.initCause(failure);
            throw refused;
        }
    }

    private static JdbcResourceExecutionGate.RefusedException refused(String reason) {
        return new JdbcResourceExecutionGate.RefusedException(reason);
    }

    @Override public void close() { context = null; }
    @Override public String toString() { return "WorkflowOwnerResourceFence[redacted]"; }
}
