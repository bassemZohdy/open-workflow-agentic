package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.event.JdbcInboxDispatch.*;

/** Task-fenced matching/dispatch/ACK. No connection spans an audit, callback or idle partial all-listen. */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcOwnedInbox {
    private static final Subject RECOVERY=new Subject(SubjectKind.SERVICE,"system:event-recovery");
    private final DataSource dataSource;
    private final JdbcInboxTaskLease leases;
    private final JdbcEventInbox inbox;
    private final JdbcInboxCheckpointProof proof;
    private final AuditSink audit;
    private final Clock clock;
    private record Row(String state,int attempts,Instant deadline,String task,String owner,Long epoch) { }

    @Inject public JdbcOwnedInbox(DataSource dataSource,JdbcInboxTaskLease leases,JdbcEventInbox inbox,
                                 JdbcInboxCheckpointProof proof,AuditTrail audit) {
        this(dataSource,leases,inbox,proof,audit,Clock.systemUTC());
    }
    public JdbcOwnedInbox(DataSource dataSource,JdbcInboxTaskLease leases,JdbcEventInbox inbox,
                          JdbcInboxCheckpointProof proof,AuditSink audit,Clock clock) {
        this.dataSource=Objects.requireNonNull(dataSource); this.leases=Objects.requireNonNull(leases);
        this.inbox=Objects.requireNonNull(inbox); this.proof=Objects.requireNonNull(proof);
        this.audit=Objects.requireNonNull(audit); this.clock=Objects.requireNonNull(clock);
    }

    /** The SDK evaluated this exact candidate's predicate outside the transaction; no different receipt is substituted. */
    public Optional<InboxMessage> match(InboxTaskClaim claim,InboxWait wait,InboxMessage candidate) {
        requireTask(claim,wait);
        return Transactions.inTransaction(dataSource,c-> {
            leases.requireCurrent(c,claim,true);
            var matched=inbox.matchCandidate(c,wait,candidate);
            leases.requireCurrent(c,claim,true); // Also fences expiry while waiting for delivery/message locks.
            return matched;
        });
    }

    /**
     * One permission per registration/owner epoch. An ambiguous/audit-failed attempt is retained; a fresh epoch is
     * required to retry. No callback return is ever used as proof. The coordinator must serialize callbacks by task.
     */
    public Optional<Reserved> reserve(InboxTaskClaim claim,InboxWait wait) {
        requireTask(claim,wait);
        if(acknowledge(wait)==Acknowledgement.ACKNOWLEDGED) return Optional.empty();
        Optional<Reserved> pending=Transactions.inTransaction(dataSource,c-> {
            leases.requireCurrent(c,claim,true); var registry=instance(c,wait); Row row=row(c,wait);
            if(row==null || terminal(row.state)) return Optional.empty();
            var receipt=signal(c,wait);
            if(receipt.isPresent() && proof.consumed(c,wait,receipt.get())) return Optional.empty();
            if(ownedBy(row,claim)) return Optional.empty(); // A lost reply never authorizes a second callback.
            if(!registry.status().equals("WAITING")) return Optional.empty();
            Instant now=databaseNow(c);
            if(!row.deadline.isAfter(now) || (registry.deadline()!=null && !registry.deadline().isAfter(now))) {
                dispose(c,wait,"EXPIRED"); return Optional.empty();
            }
            if(receipt.isEmpty()) return Optional.empty();
            leases.requireCurrent(c,claim,true);
            try(var s=c.prepareStatement("""
                    update inbox.delivery set state='DISPATCHED',attempts=attempts+1,reserved_at=clock_timestamp(),
                      task_digest=?,claim_owner=?,claim_epoch=? where tenant=? and instance_id=? and wait_key=? returning attempts,deadline
                    """)) {
                s.setString(1,claim.task().digest()); s.setString(2,claim.owner()); s.setLong(3,claim.epoch());
                s.setString(4,wait.tenant()); s.setString(5,wait.instanceId().value()); s.setString(6,wait.key());
                try(var r=s.executeQuery()) { r.next(); return Optional.of(new Reserved(wait,receipt.get(),r.getInt(1),r.getTimestamp(2).toInstant())); }
            }
        });
        if(pending.isEmpty()) { acknowledge(wait); return Optional.empty(); }
        record(pending.get(),claim,"event.dispatch.attempt"); // Transaction is closed; audit must commit before release.
        boolean permission=Transactions.inTransaction(dataSource,c-> {
            leases.requireCurrent(c,claim,false); Row current=row(c,wait);
            if(current==null || !current.state.equals("DISPATCHED") || current.attempts!=pending.get().attempt() || !ownedBy(current,claim))
                return false;
            var receipt=signal(c,wait);
            if(receipt.isEmpty() || !receipt.get().fingerprint().equals(pending.get().receipt().fingerprint()))
                throw new IllegalStateException("reserved receipt changed");
            if(proof.consumed(c,wait,receipt.get())) return false;
            leases.requireCurrent(c,claim,false); // Fresh time AFTER all blocking reads/audit; reservation won its deadline.
            return true;
        });
        if(!permission) { acknowledge(wait); return Optional.empty(); }
        return pending;
    }

    /** Historical consumption is authoritative even after completion/lease loss. ACK alone never grants advancement. */
    public Acknowledgement acknowledge(InboxWait wait) {
        Objects.requireNonNull(wait);
        Optional<Reserved> consumed=Transactions.inTransaction(dataSource,c-> {
            instance(c,wait); Row row=row(c,wait);
            if(row==null || !(row.state.equals("DISPATCHED") || row.state.equals("ACKNOWLEDGED"))) return Optional.empty();
            return signal(c,wait).filter(m-> {
                try { return proof.consumed(c,wait,m); }
                catch(SQLException unavailable) { throw new io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException("owned proof read failed",unavailable); }
            }).map(m->new Reserved(wait,m,row.attempts,row.deadline));
        });
        if(consumed.isEmpty()) return Acknowledgement.NO_PROOF;
        record(consumed.get(),null,"event.checkpoint.observed");
        return Transactions.inTransaction(dataSource,c-> {
            instance(c,wait); Row row=row(c,wait);
            if(row==null || !(row.state.equals("DISPATCHED") || row.state.equals("ACKNOWLEDGED"))) return Acknowledgement.NO_RESERVATION;
            var receipt=signal(c,wait);
            if(receipt.isEmpty() || !proof.consumed(c,wait,receipt.get())) return Acknowledgement.NO_PROOF;
            if(row.state.equals("DISPATCHED")) {
                try(var s=c.prepareStatement("update inbox.delivery set state='ACKNOWLEDGED',acknowledged_at=clock_timestamp() "
                        +"where tenant=? and instance_id=? and wait_key=?")) { route(s,wait); s.executeUpdate(); }
            }
            return Acknowledgement.ACKNOWLEDGED;
        });
    }

    private static Row row(Connection c,InboxWait wait) throws SQLException {
        try(var s=c.prepareStatement("select state,attempts,deadline,task_digest,claim_owner,claim_epoch,identity_digest "
                +"from inbox.delivery where tenant=? and instance_id=? and wait_key=? for update")) {
            route(s,wait);
            try(var r=s.executeQuery()) {
                if(!r.next()) return null;
                if(!wait.digest().equals(r.getString(7))) throw new IllegalStateException("wait identity changed");
                long epoch=r.getLong(6); Long fenced=r.wasNull()?null:epoch;
                return new Row(r.getString(1),r.getInt(2),r.getTimestamp(3).toInstant(),r.getString(4),r.getString(5),fenced);
            }
        }
    }
    private static boolean ownedBy(Row row,InboxTaskClaim claim) {
        return claim.task().digest().equals(row.task) && claim.owner().equals(row.owner) && Objects.equals(claim.epoch(),row.epoch);
    }
    private static void requireTask(InboxTaskClaim claim,InboxWait wait) {
        Objects.requireNonNull(claim); Objects.requireNonNull(wait);
        if(!wait.task().equals(claim.task())) throw new IllegalArgumentException("claim belongs to another task occurrence");
    }
    private void record(Reserved reservation,InboxTaskClaim claim,String action) {
        var wait=reservation.occurrence(); Map<String,String> values=new LinkedHashMap<>();
        values.put(AuditKeys.TENANT,wait.tenant()); values.put(AuditKeys.INSTANCE,wait.instanceId().value());
        values.put("event.wait",wait.key()); values.put("event.receipt",reservation.receipt().fingerprint());
        values.put("event.attempt",Integer.toString(reservation.attempt()));
        if(claim!=null) { values.put("event.claim.owner",claim.owner()); values.put("event.claim.epoch",Long.toString(claim.epoch())); }
        audit.record(AuditDraft.of(UlidGenerator.shared().next(),clock.instant(),RECOVERY,action,"event-delivery",wait.digest(),values));
    }
}
