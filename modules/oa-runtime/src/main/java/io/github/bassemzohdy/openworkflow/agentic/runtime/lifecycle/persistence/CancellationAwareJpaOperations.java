package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import io.serverlessworkflow.impl.*;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Serializes actual SDK mutations with cancellation cleanup in the SAME JPA writer transaction. */
@ApplicationScoped @Alternative @Priority(1)
public class CancellationAwareJpaOperations extends JpaInstanceOperations {
    private static final int LOCK_CLASS = 0x4f414350; // OACP, distinct two-int namespace from effects/inbox.
    @Inject DataSource checkpointDataSource;
    @Inject TransactionManager checkpointTransactions;

    private void lock(WorkflowContextData workflow, boolean cleanup) {
        try {
            if (checkpointTransactions.getStatus() != Status.STATUS_ACTIVE)
                throw new IllegalStateException("actual SDK writer transaction required");
            String app = workflow.definition().application().id();
            String id = workflow.instanceData().id();
            // A hash collision only serializes unrelated instances. No retained connection/lock after JTA completion.
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((app.length()+":"+app+id.length()+":"+id).getBytes(StandardCharsets.UTF_8));
            try (var c = checkpointDataSource.getConnection();
                 var s = c.prepareStatement("select pg_advisory_xact_lock(?,?)")) {
                s.setInt(1,LOCK_CLASS); s.setInt(2,ByteBuffer.wrap(digest).getInt()); s.execute();
            }
            // Check after acquiring the lock. If cancellation races a write already inside, its cleanup waits
            // for that transaction and removes its rows; a write arriving after cleanup cannot resurrect them.
            if (!cleanup && workflow.instanceData().status() == WorkflowStatus.CANCELLED)
                throw new CancelledCheckpoint();
        } catch (SQLException | SystemException unavailable) {
            throw new StoreUnavailableException("SDK checkpoint serialization unavailable",unavailable);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void writeInstanceData(WorkflowContextData w) { lock(w,false); super.writeInstanceData(w); }
    @Override public void writeCompletedTask(WorkflowContextData w, TaskContextData t) { lock(w,false); super.writeCompletedTask(w,t); }
    @Override public void writeRetryTask(WorkflowContextData w, TaskContextData t) { lock(w,false); super.writeRetryTask(w,t); }
    @Override public void writeStatus(WorkflowContextData w, WorkflowStatus s) { lock(w,false); super.writeStatus(w,s); }
    @Override public void clearStatus(WorkflowContextData w) { lock(w,false); super.clearStatus(w); }
    @Override public void removeProcessInstance(WorkflowContextData w) { lock(w,true); super.removeProcessInstance(w); }

    /** Never interpreted as successful persistence; the gate holds only when trusted SDK state confirms cancellation. */
    static final class CancelledCheckpoint extends IllegalStateException {
        CancelledCheckpoint() { super("SDK workflow cancelled before checkpoint"); }
    }
}
