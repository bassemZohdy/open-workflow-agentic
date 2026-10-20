package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.serverlessworkflow.impl.TaskContextData;
import io.serverlessworkflow.impl.WorkflowContextData;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.concurrent.*;

/** Actual receipt ACK followed by live ownership, between committed SDK persistence and advancement. */
@ApplicationScoped
public class InboxCheckpointFinalizer implements CheckpointFinalizer,AutoCloseable {
    private final JdbcOwnedInbox inbox;
    private final JdbcInboxTaskLease leases;
    private final ThreadPoolExecutor executor;

    @Inject public InboxCheckpointFinalizer(JdbcOwnedInbox inbox,JdbcInboxTaskLease leases) {
        this.inbox=Objects.requireNonNull(inbox);this.leases=Objects.requireNonNull(leases);
        executor=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{
            var thread=new Thread(r,"inbox-checkpoint-finalize");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public Prepared prepare(PersistenceCommitGate.Operation operation,WorkflowContextData workflow,TaskContextData task) {
        if(operation!=PersistenceCommitGate.Operation.TASK_COMPLETED) return ()->CompletableFuture.completedFuture(null);
        var markers=InboxCheckpointMarker.forCheckpoint(workflow,Objects.requireNonNull(task));
        if(markers.isEmpty()) return ()->CompletableFuture.completedFuture(null);
        // Everything used by the worker is immutable; never reread later-mutated task/metadata objects.
        var claims=markers.stream().map(InboxCheckpointMarker::claim).distinct().toList();
        return ()->CompletableFuture.runAsync(()-> {
            for(var marker:markers) {
                if(inbox.acknowledge(marker.occurrence())!=JdbcInboxDispatch.Acknowledgement.ACKNOWLEDGED)
                    throw new IllegalStateException("committed inbox checkpoint lacks authoritative receipt ACK");
            }
            for(var claim:claims) leases.requireLive(claim); // AFTER all blocking proof/audit/ACK work.
        },executor);
    }

    @Override @PreDestroy public void close() { executor.shutdownNow(); }
}
