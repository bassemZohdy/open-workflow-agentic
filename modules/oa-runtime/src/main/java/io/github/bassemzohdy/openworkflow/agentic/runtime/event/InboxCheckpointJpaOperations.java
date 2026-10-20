package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CancellationAwareJpaOperations;
import io.serverlessworkflow.impl.TaskContextData;
import io.serverlessworkflow.impl.WorkflowContextData;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

/** Preserves the original JPA read/write implementation and adds receipt proof inside the writer's SAME transaction. */
@ApplicationScoped @Alternative @Priority(2)
public class InboxCheckpointJpaOperations extends CancellationAwareJpaOperations {
    @Inject JdbcInboxCheckpointProof receipts;

    @Override public void writeCompletedTask(WorkflowContextData workflow, TaskContextData task) {
        super.writeCompletedTask(workflow,task);
        for (var marker : InboxCheckpointMarker.forCheckpoint(workflow,task)) receipts.record(marker);
        // Any proof/fence failure propagates through the real writer future and rolls back BOTH SDK checkpoint and proof.
    }
}
