package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.impl.WorkflowContextData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * A fresh, fail-closed owner check for an SDK execution boundary. It is not installed in application execution.
 * The caller must arrange trusted context bindings and lease renewal. A successful check is not a lock on the
 * subsequent task or external effect; the lease can expire immediately afterward.
 */
@ApplicationScoped
public class WorkflowOwnerExecutionFence {
    private final WorkflowOwnerContextBindings bindings;
    private final TransactionManager transactions;
    private final JdbcWorkflowOwnerLease leases;

    @Inject public WorkflowOwnerExecutionFence(WorkflowOwnerContextBindings bindings, TransactionManager transactions,
                                               DataSource data) {
        this.bindings = Objects.requireNonNull(bindings); this.transactions = Objects.requireNonNull(transactions);
        this.leases = new JdbcWorkflowOwnerLease(Objects.requireNonNull(data));
    }

    public void requireCurrent(WorkflowContextData context) {
        var claim = bindings.require(context);
        WorkflowOwnerJpaFence.requireMatchingContext(context, claim);
        Transactions.inSuspendedTransaction(transactions, () -> {
            leases.withCurrentLease(claim, connection -> null);
            return null;
        });
        if (!bindings.require(context).equals(claim))
            throw new IllegalStateException("local owner generation changed during execution check");
    }
}
