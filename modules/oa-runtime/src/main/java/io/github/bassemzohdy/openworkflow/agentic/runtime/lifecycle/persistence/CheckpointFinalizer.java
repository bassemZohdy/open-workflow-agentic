package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.serverlessworkflow.impl.TaskContextData;
import io.serverlessworkflow.impl.WorkflowContextData;
import java.util.concurrent.CompletableFuture;

/**
 * Snapshot trusted completion work BEFORE submitting a mutable SDK context to its writer. prepare must do no I/O.
 * Prepared work runs only AFTER actual writer success and must return promptly with an asynchronous stage. Neither
 * observer notification nor SDK advancement is released until that stage succeeds within the checkpoint watchdog.
 */
@FunctionalInterface
public interface CheckpointFinalizer {
    Prepared prepare(PersistenceCommitGate.Operation operation,WorkflowContextData workflow,TaskContextData task);

    @FunctionalInterface interface Prepared { CompletableFuture<Void> afterCommit(); }

    static CheckpointFinalizer none() { return (op,w,t)->()->CompletableFuture.completedFuture(null); }
}
