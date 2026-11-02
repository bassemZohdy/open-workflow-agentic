package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.impl.WorkflowContextData;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CancellationAwareJpaOperations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Candidate for checking an explicit owner claim inside the actual SDK writer's JTA transaction. Not installed in the
 * production writer or a claim source. The caller must supply a trusted claim for this particular execution, hold no
 * external effects in this transaction, and check both before and after its database work. The enlisted row locks are
 * held until the surrounding JTA transaction completes; a failed SDK listener may still be ignored by the SDK.
 */
@ApplicationScoped
public class WorkflowOwnerJpaFence {
    private final DataSource data;
    private final TransactionManager transactions;
    private final CancellationAwareJpaOperations checkpointOperations;

    @Inject public WorkflowOwnerJpaFence(DataSource data, TransactionManager transactions,
                                         CancellationAwareJpaOperations checkpointOperations) {
        this.data = Objects.requireNonNull(data); this.transactions = Objects.requireNonNull(transactions);
        this.checkpointOperations = Objects.requireNonNull(checkpointOperations);
    }

    /** Validates immutable SDK identity; this check alone is not proof of a live lease. */
    static void requireMatchingContext(WorkflowContextData context, JdbcWorkflowOwnerLease.Claim claim) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(claim, "claim");
        var binding = claim.binding();
        var definition = context.definition().id();
        if (!binding.applicationId().equals(context.definition().application().id())
                || !binding.engineInstanceId().equals(context.instanceData().id())
                || !binding.definition().definitionId().equals(definition.namespace() + "/" + definition.name())
                || !binding.definition().version().equals(definition.version()))
            throw new IllegalStateException("workflow owner claim does not identify this SDK context");
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void requireCurrent(WorkflowContextData context, JdbcWorkflowOwnerLease.Claim claim) {
        requireMatchingContext(context, claim);
        try {
            if (transactions.getStatus() != Status.STATUS_ACTIVE)
                throw new IllegalStateException("active SDK writer transaction required");
            // Cancellation acquires this advisory lock before the registry row; preserve that order here.
            checkpointOperations.lockForOwnerFence(context);
            try (var connection = data.getConnection()) {
                JdbcWorkflowOwnerLease.requireCurrent(connection, claim);
            }
        } catch (SQLException | SystemException unavailable) {
            throw new StoreUnavailableException("workflow owner checkpoint fence unavailable", unavailable);
        }
    }
}
