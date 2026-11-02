package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditKeys;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Retains the original draft id and time for one immutable actual checkpoint proof. Caller owns transaction. */
final class CheckpointAuditSource {
    private static final Subject ACTOR=new Subject(SubjectKind.SERVICE,"system:event-recovery");
    private CheckpointAuditSource() { }
    static AuditEvent draft(Connection c,String tenant,Ulid instance,String waitKey,String digest,String fingerprint,
                            int attempt,Clock clock) throws SQLException {
        if(c.getAutoCommit())throw new IllegalArgumentException("an existing transaction is required");
        var id=UlidGenerator.shared().next();var time=clock.instant().truncatedTo(ChronoUnit.MICROS);
        try(var q=c.prepareStatement("insert into inbox.checkpoint_audit_source "
                +"(tenant,instance_id,wait_key,identity_digest,receipt_fingerprint,dispatch_attempt,event_id,occurred_at) "
                +"values (?,?,?,?,?,?,?,?) on conflict (tenant,instance_id,wait_key) do nothing")) {
            q.setString(1,tenant);q.setString(2,instance.value());q.setString(3,waitKey);q.setString(4,digest);
            q.setString(5,fingerprint);q.setInt(6,attempt);q.setString(7,id.value());q.setTimestamp(8,java.sql.Timestamp.from(time));q.executeUpdate();
        }
        try(var q=c.prepareStatement("select identity_digest,receipt_fingerprint,dispatch_attempt,event_id,occurred_at "
                +"from inbox.checkpoint_audit_source where tenant=? and instance_id=? and wait_key=?")) {
            q.setString(1,tenant);q.setString(2,instance.value());q.setString(3,waitKey);
            try(var r=q.executeQuery()) {
                if(!r.next()||!digest.equals(r.getString(1))||!fingerprint.equals(r.getString(2))||attempt!=r.getInt(3))
                    throw new IllegalStateException("checkpoint audit source differs from retained proof");
                id=Ulid.parse(r.getString(4));time=r.getTimestamp(5).toInstant();
            }
        }
        return AuditDraft.of(id,time,ACTOR,"event.checkpoint.observed","event-delivery",digest,
                Map.of(AuditKeys.TENANT,tenant,AuditKeys.INSTANCE,instance.value(),"event.wait",waitKey,
                        "event.receipt",fingerprint,"event.attempt",Integer.toString(attempt)));
    }
}
