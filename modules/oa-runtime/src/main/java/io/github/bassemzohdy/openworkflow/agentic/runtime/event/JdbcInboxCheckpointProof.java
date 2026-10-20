package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.sql.*;
import javax.sql.DataSource;

/** Receipt proof committed atomically with the actual SDK task checkpoint, never from a lifecycle notification. */
@ApplicationScoped
public class JdbcInboxCheckpointProof implements InboxCheckpointProof {
    @Inject DataSource dataSource;
    @Inject TransactionManager transactions;

    @Transactional(Transactional.TxType.MANDATORY)
    public void record(InboxCheckpointMarker marker) {
        try {
            if (transactions.getStatus() != Status.STATUS_ACTIVE) throw new IllegalStateException("SDK checkpoint transaction required");
            try (Connection c = dataSource.getConnection()) {
                InboxWait w = marker.occurrence();
                // Same lock order as dispatch/cancellation. Holding it until JTA commit linearizes checkpoint vs cancellation/retry.
                try (var s = c.prepareStatement("select status,definition_id,definition_version,engine_instance_id from task.instance "
                        + "where tenant=? and id=? for update")) {
                    s.setString(1,w.tenant()); s.setString(2,w.instanceId().value());
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !(r.getString(1).equals("WAITING") || r.getString(1).equals("RUNNING"))
                                || !r.getString(2).equals(w.namespace()+"/"+w.definition()) || !r.getString(3).equals(w.version())
                                || !w.engineInstanceId().equals(r.getString(4))) {
                            throw new IllegalStateException("checkpoint lost instance authority");
                        }
                    }
                }
                try (var s = c.prepareStatement("select d.state,d.attempts,d.identity_digest,m.fingerprint "
                        + "from inbox.delivery d join inbox.signal s using(tenant,instance_id,wait_key) "
                        + "join inbox.message m on m.tenant=s.tenant and m.source=s.source and m.event_id=s.event_id "
                        + "where d.tenant=? and d.instance_id=? and d.wait_key=? for update of d")) {
                    route(s,w);
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !r.getString(1).equals("DISPATCHED") || r.getInt(2) != marker.attempt()
                                || !r.getString(3).equals(w.digest()) || !r.getString(4).equals(marker.receiptFingerprint())) {
                            throw new IllegalStateException("checkpoint lost its exact dispatch/receipt fence");
                        }
                    }
                }
                try (var s = c.prepareStatement("""
                        insert into inbox.checkpoint_receipt(tenant,instance_id,wait_key,identity_digest,receipt_fingerprint,
                          dispatch_attempt,application_id,engine_instance_id,task_position,iteration)
                        values(?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    route(s,w); s.setString(4,w.digest()); s.setString(5,marker.receiptFingerprint()); s.setInt(6,marker.attempt());
                    s.setString(7,w.applicationId()); s.setString(8,w.engineInstanceId()); s.setString(9,w.taskPosition());
                    s.setInt(10,w.iteration()); s.executeUpdate();
                }
                // No commit/setAutoCommit here: the Agroal connection participates in JpaInstanceWriter's REQUIRES_NEW JTA.
            }
        } catch (SQLException | SystemException unavailable) {
            throw new StoreUnavailableException("atomic inbox checkpoint proof failed",unavailable);
        }
    }

    @Override @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean consumed(InboxWait w, InboxMessage receipt) {
        if (!receipt.tenant().equals(w.tenant()) || !receipt.instanceId().equals(w.instanceId())
                || !receipt.correlationKey().equals(w.key()) || !receipt.type().equals(w.eventType())) return false;
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                select 1 from inbox.checkpoint_receipt where tenant=? and instance_id=? and wait_key=?
                and identity_digest=? and receipt_fingerprint=? and application_id=? and engine_instance_id=?
                and task_position=? and iteration=?
                """)) {
            route(s,w); s.setString(4,w.digest()); s.setString(5,receipt.fingerprint()); s.setString(6,w.applicationId());
            s.setString(7,w.engineInstanceId()); s.setString(8,w.taskPosition()); s.setInt(9,w.iteration());
            try (var r = s.executeQuery()) { return r.next(); }
        } catch (SQLException unavailable) { throw new StoreUnavailableException("inbox checkpoint proof unavailable",unavailable); }
    }
    private static void route(PreparedStatement s, InboxWait w) throws SQLException {
        s.setString(1,w.tenant()); s.setString(2,w.instanceId().value()); s.setString(3,w.key());
    }
}
