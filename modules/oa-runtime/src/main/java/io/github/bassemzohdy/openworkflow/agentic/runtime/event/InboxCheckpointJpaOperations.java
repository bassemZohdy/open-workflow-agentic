package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import io.serverlessworkflow.impl.TaskContextData;
import io.serverlessworkflow.impl.WorkflowContextData;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

/** Preserves the original JPA read/write implementation and adds receipt proof inside the writer's SAME transaction. */
@ApplicationScoped @Alternative @Priority(1)
public class InboxCheckpointJpaOperations extends JpaInstanceOperations {
    @Inject JdbcInboxCheckpointProof receipts;

    @Override public void writeCompletedTask(WorkflowContextData workflow, TaskContextData task) {
        super.writeCompletedTask(workflow,task);
        for (var marker : InboxCheckpointMarker.forCheckpoint(workflow,task)) receipts.record(marker);
        // Any proof/fence failure propagates through the real writer future and rolls back BOTH SDK checkpoint and proof.
    }
}
