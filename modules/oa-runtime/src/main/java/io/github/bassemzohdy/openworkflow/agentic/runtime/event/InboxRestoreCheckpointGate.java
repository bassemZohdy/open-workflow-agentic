package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PersistenceCommitGate;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** One bounded repair batch per reconciler pass, before the original reader's restored work can advance. */
@ApplicationScoped
public class InboxRestoreCheckpointGate implements RestoreCheckpointGate {
    private final JdbcInboxCheckpointRecovery recovery;
    private final WorkflowApplication application;
    private final PersistenceCommitGate checkpoints;
    @Inject public InboxRestoreCheckpointGate(JdbcInboxCheckpointRecovery recovery,WorkflowApplication application,PersistenceCommitGate checkpoints) {
        this.recovery=recovery;this.application=application;this.checkpoints=checkpoints;
    }
    public boolean prepare(InstanceRecord instance) {
        checkpoints.requireAvailable();
        try { return recovery.repairBatch(instance,application.id(),64); }
        catch(RuntimeException failure) { checkpoints.recoveryFailed(failure);throw failure; }
    }
}
