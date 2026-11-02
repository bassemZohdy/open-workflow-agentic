package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CancellablePersistenceReader;
import io.serverlessworkflow.impl.WorkflowContextData;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit candidate for acquiring a lease for a context produced by a trusted restore reader. Not installed in
 * application startup. The required post-acquisition check must revalidate authority, deadlines and checkpoint
 * preparation after any blocking lease I/O. It cannot fence in-flight work or make start atomic with that check.
 */
public final class RestoredWorkflowOwnerStarter implements CancellablePersistenceReader.OwnerStarter {
    @FunctionalInterface public interface PostAcquireCheck { boolean mayStart(InstanceRecord current); }
    private final RegistryScan scan;
    private final InstanceStore store;
    private final WorkflowOwnerCoordinator coordinator;
    private final WorkflowOwnerExecutionFence fence;
    private final Duration ttl;
    private final PostAcquireCheck check;

    public RestoredWorkflowOwnerStarter(RegistryScan scan, InstanceStore store, WorkflowOwnerCoordinator coordinator,
                                        WorkflowOwnerExecutionFence fence, Duration ttl, PostAcquireCheck check) {
        this.scan = Objects.requireNonNull(scan); this.store = Objects.requireNonNull(store);
        this.coordinator = Objects.requireNonNull(coordinator); this.fence = Objects.requireNonNull(fence);
        this.ttl = Objects.requireNonNull(ttl); this.check = Objects.requireNonNull(check);
    }
    @Override public Optional<WorkflowOwnerCoordinator.Session> acquire(WorkflowContextData context) {
        Objects.requireNonNull(context);
        String engineId = context.instanceData().id();
        var ref = scan.byEngineInstanceId(engineId).orElse(null);
        if (ref == null) return Optional.empty();
        var row = store.find(ref.tenant(), Ulid.parse(ref.id())).orElse(null);
        if (row == null || !Set.of(InstanceStatus.RUNNING, InstanceStatus.WAITING).contains(row.status())
                || !engineId.equals(row.engineInstanceId())) return Optional.empty();
        var binding = new JdbcWorkflowOwnerLease.Binding(row.tenant(), row.id(), context.definition().application().id(),
                engineId, row.definition());
        var session = coordinator.acquire(binding, context, ttl);
        if (session.isEmpty()) return Optional.empty();
        try {
            var current = store.find(row.tenant(), row.id()).orElse(null);
            if (current == null || !Set.of(InstanceStatus.RUNNING, InstanceStatus.WAITING).contains(current.status())
                    || !row.engineInstanceId().equals(current.engineInstanceId()) || !row.definition().equals(current.definition())
                    || !row.owner().equals(current.owner()) || !check.mayStart(current)) {
                throw new IllegalStateException("restored workflow failed its post-acquisition check");
            }
            fence.requireCurrent(context); // The potentially blocking post-acquisition checks may have outlasted the lease.
            return session;
        } catch (RuntimeException failure) {
            session.get().close();
            throw new CancellablePersistenceReader.RetiredOwnerContextException("restored owner context is retired", failure);
        } catch (Error failure) {
            session.get().close(); throw failure;
        }
    }
}
