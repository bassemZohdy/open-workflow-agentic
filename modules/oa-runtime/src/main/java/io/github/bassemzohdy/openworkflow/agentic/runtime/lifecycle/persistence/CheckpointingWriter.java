package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.serverlessworkflow.impl.TaskContextData;
import io.serverlessworkflow.impl.WorkflowContextData;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceWriter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PersistenceCommitGate.Operation.*;

/** Wraps the actual JPA writer future, preserving SDK persistence/exclusion behavior in the existing customizer. */
public final class CheckpointingWriter implements PersistenceInstanceWriter {
    private final PersistenceInstanceWriter delegate;
    private final PersistenceCommitGate gate;
    private final CheckpointCommitObserver observer;

    public CheckpointingWriter(PersistenceInstanceWriter delegate, PersistenceCommitGate gate, CheckpointCommitObserver observer) {
        this.delegate = Objects.requireNonNull(delegate); this.gate = Objects.requireNonNull(gate); this.observer = Objects.requireNonNull(observer);
    }
    private CompletableFuture<Void> write(PersistenceCommitGate.Operation op, WorkflowContextData workflow, TaskContextData task,
                                          Supplier<CompletableFuture<Void>> action) {
        var definition = workflow.definition().id();
        var checkpoint = new CheckpointCommitObserver.Checkpoint(workflow.instanceData().id(), definition.namespace(),
                definition.name(), definition.version(), task == null ? null : task.position().jsonPointer(), task == null ? 0 : task.iteration());
        return gate.commit(op, action, () -> observer.committed(op, checkpoint));
    }
    public CompletableFuture<Void> started(WorkflowContextData w) { return write(STARTED, w, null, () -> delegate.started(w)); }
    public CompletableFuture<Void> completed(WorkflowContextData w) { return write(COMPLETED, w, null, () -> delegate.completed(w)); }
    public CompletableFuture<Void> failed(WorkflowContextData w, Throwable error) { return write(FAILED, w, null, () -> delegate.failed(w, error)); }
    public CompletableFuture<Void> aborted(WorkflowContextData w) { return write(ABORTED, w, null, () -> delegate.aborted(w)); }
    public CompletableFuture<Void> suspended(WorkflowContextData w) { return write(SUSPENDED, w, null, () -> delegate.suspended(w)); }
    public CompletableFuture<Void> resumed(WorkflowContextData w) { return write(RESUMED, w, null, () -> delegate.resumed(w)); }
    public CompletableFuture<Void> taskRetried(WorkflowContextData w, TaskContextData t) { return write(TASK_RETRIED, w, t, () -> delegate.taskRetried(w, t)); }
    public CompletableFuture<Void> taskStarted(WorkflowContextData w, TaskContextData t) { return write(TASK_STARTED, w, t, () -> delegate.taskStarted(w, t)); }
    public CompletableFuture<Void> taskCompleted(WorkflowContextData w, TaskContextData t) { return write(TASK_COMPLETED, w, t, () -> delegate.taskCompleted(w, t)); }
    public void close() throws Exception { delegate.close(); }
}
