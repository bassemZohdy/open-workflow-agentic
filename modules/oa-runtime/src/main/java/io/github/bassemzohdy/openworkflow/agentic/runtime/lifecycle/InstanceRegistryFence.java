package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import java.util.Objects;
import java.util.Optional;

/**
 * The registry part of the OWNER_AND_LEASE check of {@link CompositeExecutionFence}: the live instance row must still
 * exist, be RUNNING, and agree with the entry's scope on tenant, full owner, engine instance and definition. A cancelled,
 * finished, waiting or replaced instance, a different owner, a lookup that fails and a missing engine id all refuse.
 *
 * <p>It reads the row afresh on every call (no cache) and holds only identifiers. It does NOT establish execution
 * ownership by a replica (a lease); that stays an open gate, so this alone must not be treated as the whole check. The
 * definition is compared as {@code namespace/name} plus version, the form {@link DefinitionRef} already uses.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class InstanceRegistryFence implements ResourceExecutionFence {

    /** Reads the current row of an instance of a tenant. */
    @FunctionalInterface
    public interface Lookup {
        Optional<InstanceRecord> find(String tenant, Ulid instanceId);
    }

    private final Lookup lookup;

    public InstanceRegistryFence(Lookup lookup) { this.lookup = Objects.requireNonNull(lookup, "lookup"); }

    public static InstanceRegistryFence over(InstanceStore store) {
        Objects.requireNonNull(store, "store");
        return new InstanceRegistryFence(store::find);
    }

    @Override public void requireCurrent(ResourceExecutionEntry entry) {
        ResourceCallScope scope = Objects.requireNonNull(entry, "entry").scope();
        InstanceRecord row;
        try {
            row = lookup.find(scope.tenant(), scope.instanceId()).orElse(null);
        } catch (RuntimeException e) {
            var refused = new JdbcResourceExecutionGate.RefusedException("instance registry unavailable");
            refused.initCause(e);
            throw refused;
        }
        if (row == null) throw refuse("instance not found");
        if (!row.tenant().equals(scope.tenant())) throw refuse("tenant differs");
        if (!row.id().equals(scope.instanceId())) throw refuse("instance differs");
        if (row.status() != InstanceStatus.RUNNING) throw refuse("instance is not running");
        if (!row.owner().equals(scope.owner())) throw refuse("owner differs");
        if (row.engineInstanceId() == null || !row.engineInstanceId().equals(scope.engineInstanceId()))
            throw refuse("engine instance differs");
        if (!row.definition().definitionId().equals(scope.namespace() + "/" + scope.definition())
                || !row.definition().version().equals(scope.version()))
            throw refuse("definition differs");
    }

    private static JdbcResourceExecutionGate.RefusedException refuse(String reason) {
        return new JdbcResourceExecutionGate.RefusedException(reason);
    }

    @Override public String toString() { return "InstanceRegistryFence[redacted]"; }
}
