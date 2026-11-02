package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditDraftFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Append one retained checkpoint audit draft, in the caller's transaction or a short independent transaction. */
public final class CheckpointRecoveryAudit {
    private CheckpointRecoveryAudit() { }
    public static void record(DataSource source,AuditEvent event) {
        Transactions.inTransaction(source,c->{append(c,event);return null;});
    }
    public static void append(Connection connection, AuditEvent event) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(event);
        if (connection.getAutoCommit()) throw new IllegalArgumentException("an existing transaction is required");
        if (!"event.checkpoint.observed".equals(event.action()) || !"event-delivery".equals(event.resourceType()))
            throw new IllegalArgumentException("not a checkpoint recovery audit");
        try(var q=connection.prepareStatement("select tenant,instance_id,wait_key,identity_digest,receipt_fingerprint,"
                +"dispatch_attempt,occurred_at from inbox.checkpoint_audit_source where event_id=?")) {
            q.setString(1,event.id().value());
            try(var r=q.executeQuery()) {
                var a=event.attributes();
                if(!r.next()||!r.getString(1).equals(a.get(AuditKeys.TENANT))||!r.getString(2).equals(a.get(AuditKeys.INSTANCE))
                        ||!r.getString(3).equals(a.get("event.wait"))||!r.getString(4).equals(event.resourceId())
                        ||!r.getString(5).equals(a.get("event.receipt"))||!Integer.toString(r.getInt(6)).equals(a.get("event.attempt"))
                        ||!r.getTimestamp(7).toInstant().equals(event.occurredAt())
                        ||a.size()!=5||!event.actor().equals(new io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject(
                                io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind.SERVICE,"system:event-recovery")))
                    throw new IllegalStateException("checkpoint audit does not match its retained source");
            }
        }
        // Serialize this source without holding an inbox lock. Other audit chains use distinct advisory-lock spaces.
        try(var q=connection.prepareStatement("select pg_advisory_xact_lock(?,hashtext(?))")) {
            q.setInt(1,0x4f414331);q.setString(2,event.id().value());q.execute();
        }
        try(var q=connection.prepareStatement("select "+AuditRows.EVENT_COLUMNS+" from audit.event where event_id=?")) {
            q.setString(1,event.id().value());
            try(var r=q.executeQuery()) {
                if(r.next()) {
                    var stored=AuditRows.event(r);
                    if(!AuditDraftFingerprint.of(stored).equals(AuditDraftFingerprint.of(event))
                            ||!AuditHash.compute(stored).equals(stored.hash()))
                        throw new IllegalStateException("retained checkpoint audit conflicts with its source");
                    try(var head=connection.prepareStatement("select hash from audit.head_log where chain_id=? and seq=?")) {
                        head.setString(1,AuditKeys.chainOf(stored.attributes()));head.setLong(2,stored.sequence());
                        try(var h=head.executeQuery()) {if(!h.next()||!stored.hash().equals(h.getString(1)))
                            throw new IllegalStateException("retained checkpoint audit head differs");}
                    }
                    return;
                }
            }
        }
        AuditTrail.appendInTransaction(connection, AuditKeys.chainOf(event.attributes()), event);
    }
}
