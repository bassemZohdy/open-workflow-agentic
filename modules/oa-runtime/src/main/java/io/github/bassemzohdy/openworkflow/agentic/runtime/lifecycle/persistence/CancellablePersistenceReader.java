package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowContextData;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowOwnerCoordinator;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceOperations;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceReader;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceWriter;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Explicit candidate reader for guarded restored instances. It uses the pinned SDK's persistence operations and
 * preserves the source stream's close handler. This is not installed by default and does not establish cross-replica
 * fencing, crash-safe terminal reconciliation or production readiness. Trusted composition must use exactly one
 * reader per WorkflowApplication; in-flight stale snapshots are held behind per-definition cancellation tombstones.
 * Tombstones are bounded for this reader's lifetime; saturation refuses further pending cancellation.
 */
public final class CancellablePersistenceReader implements PersistenceInstanceReader {
    private final PersistenceInstanceOperations operations;
    private final PersistenceInstanceWriter writer;
    private final Map<WorkflowDefinition, Set<String>> prohibited = new IdentityHashMap<>();
    @FunctionalInterface public interface OwnerStarter {
        Optional<WorkflowOwnerCoordinator.Session> acquire(WorkflowContextData context);
    }
    /** The context was bound and retired; a fresh restored instance is required for another attempt. */
    public static final class RetiredOwnerContextException extends IllegalStateException {
        public RetiredOwnerContextException(String message, Throwable cause) { super(message, cause); }
    }
    private final int maxTombstones;
    private final OwnerStarter ownerStarter;
    private int tombstones;
    private boolean closed;

    public CancellablePersistenceReader(PersistenceInstanceOperations operations, PersistenceInstanceWriter writer) {
        this(operations, writer, 100_000, null);
    }

    public CancellablePersistenceReader(PersistenceInstanceOperations operations, PersistenceInstanceWriter writer,
                                        int maxTombstones) {
        this(operations, writer, maxTombstones, null);
    }

    /** Explicit test composition; the application must also install execution and persistence fences. */
    public CancellablePersistenceReader(PersistenceInstanceOperations operations, PersistenceInstanceWriter writer,
                                        int maxTombstones, OwnerStarter ownerStarter) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.ownerStarter = ownerStarter;
        if (maxTombstones < 1) throw new IllegalArgumentException("invalid cancellation tombstone limit");
        this.maxTombstones = maxTombstones;
    }

    @Override public Stream<WorkflowInstance> scanAll(WorkflowDefinition definition, String applicationId) {
        return operations.scanAll(applicationId, definition)
                .flatMap(info -> materialize(definition, info).stream());
    }

    @Override public Optional<WorkflowInstance> find(WorkflowDefinition definition, String instanceId) {
        return operations.readWorkflowInfo(definition, instanceId)
                .flatMap(info -> materialize(definition, info));
    }

    private synchronized Optional<WorkflowInstance> materialize(WorkflowDefinition definition,
            io.serverlessworkflow.impl.persistence.PersistenceWorkflowInfo info) {
        if (closed) throw new IllegalStateException("restore reader is closed");
        if (prohibited.getOrDefault(definition, Set.of()).contains(info.id())) return Optional.empty();
        return Optional.of(CancellableRestoredWorkflowInstance.of(definition, info, writer, this, ownerStarter));
    }

    /** Prevents stale reads from reconstructing this occurrence, even when deletion later fails. */
    synchronized void prohibitRestore(WorkflowDefinition definition, String id) {
        if (closed) throw new IllegalStateException("restore reader is closed");
        var ids = prohibited.get(definition);
        if (ids != null && ids.contains(id)) return;
        if (tombstones >= maxTombstones) throw new IllegalStateException("cancellation tombstone limit reached");
        if (ids == null) { ids = new HashSet<>(); prohibited.put(definition, ids); }
        ids.add(id); tombstones++;
    }

    /** Operations and writer are owned by the composing application, not by this reader. */
    @Override public synchronized void close() { closed = true; }
}
