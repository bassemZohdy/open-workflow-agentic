package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Short transactions only. One task owner is shared across its all/any registrations; no idle pooled session. */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcInboxTaskLease {
    private static final Subject RECOVERY=new Subject(SubjectKind.SERVICE,"system:event-recovery");
    private final DataSource dataSource;
    private final AuditSink audit;
    private final Clock clock;
    @Inject TransactionManager transactions;
    public record Lease(InboxTaskClaim claim, Instant expiresAt) { }
    private record Row(String owner,long epoch,String state,Instant expiresAt) { }

    @Inject public JdbcInboxTaskLease(DataSource dataSource, AuditTrail audit) { this(dataSource,audit,Clock.systemUTC()); }
    public JdbcInboxTaskLease(DataSource dataSource, AuditSink audit, Clock clock) {
        this.dataSource=Objects.requireNonNull(dataSource); this.audit=Objects.requireNonNull(audit); this.clock=Objects.requireNonNull(clock);
    }

    /** Retrying a lost acquisition reply for the SAME live owner retains its epoch and expiry, then audits again. */
    public Optional<Lease> acquire(InboxTask task,String owner,Duration ttl) {
        Objects.requireNonNull(task); new InboxTaskClaim(task,owner,1); long millis=ttlMillis(ttl);
        Optional<Lease> lease=Transactions.inTransaction(dataSource,c-> {
            if(!registry(c,task,false)) return Optional.empty();
            Row old=read(c,task); Instant now=now(c);
            if(old!=null && old.state.equals("ACTIVE") && old.expiresAt.isAfter(now)) {
                return old.owner.equals(owner) ? Optional.of(new Lease(new InboxTaskClaim(task,owner,old.epoch),old.expiresAt)) : Optional.empty();
            }
            long epoch=old==null ? 1 : Math.addExact(old.epoch,1); Instant expires=now.plusMillis(millis);
            if(old==null) {
                try(var s=c.prepareStatement("""
                        insert into inbox.task_claim(tenant,instance_id,task_digest,application_id,engine_instance_id,
                          namespace,definition,version,task_position,iteration,owner,epoch,state,acquired_at,renewed_at,expires_at)
                        values(?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                        """)) {
                    key(s,task); s.setString(4,task.applicationId()); s.setString(5,task.engineInstanceId());
                    s.setString(6,task.namespace()); s.setString(7,task.definition()); s.setString(8,task.version());
                    s.setString(9,task.taskPosition()); s.setInt(10,task.iteration()); s.setString(11,owner); s.setLong(12,epoch);
                    s.setTimestamp(13,Timestamp.from(now)); s.setTimestamp(14,Timestamp.from(now)); s.setTimestamp(15,Timestamp.from(expires));
                    s.executeUpdate();
                }
            } else {
                try(var s=c.prepareStatement("update inbox.task_claim set owner=?,epoch=?,state='ACTIVE',acquired_at=?,renewed_at=?,expires_at=? "
                        +"where tenant=? and instance_id=? and task_digest=?")) {
                    s.setString(1,owner); s.setLong(2,epoch); s.setTimestamp(3,Timestamp.from(now));
                    s.setTimestamp(4,Timestamp.from(now)); s.setTimestamp(5,Timestamp.from(expires)); key(s,task,6); s.executeUpdate();
                }
            }
            return Optional.of(new Lease(new InboxTaskClaim(task,owner,epoch),expires));
        });
        record(task,owner,lease.map(l->l.claim.epoch()).orElse(0L),lease.isPresent()?"event.task.claimed":"event.task.claim.refused");
        return lease; // Audit must commit before any claim authority is returned, including retry/idempotent acquisition.
    }

    public Optional<Lease> renew(InboxTaskClaim claim,Duration ttl) {
        Objects.requireNonNull(claim); long millis=ttlMillis(ttl);
        Optional<Lease> renewed=Transactions.inTransaction(dataSource,c-> {
            if(!registry(c,claim.task(),false)) return Optional.empty();
            Row old=read(c,claim.task()); Instant now=now(c);
            if(!matches(old,claim) || !old.expiresAt.isAfter(now)) return Optional.empty();
            Instant expiry=now.plusMillis(millis); if(expiry.isBefore(old.expiresAt)) expiry=old.expiresAt;
            try(var s=c.prepareStatement("update inbox.task_claim set renewed_at=?,expires_at=? where tenant=? and instance_id=? and task_digest=?")) {
                s.setTimestamp(1,Timestamp.from(now)); s.setTimestamp(2,Timestamp.from(expiry)); key(s,claim.task(),3); s.executeUpdate();
            }
            return Optional.of(new Lease(claim,expiry));
        });
        record(claim.task(),claim.owner(),claim.epoch(),renewed.isPresent()?"event.task.renewed":"event.task.renew.refused"); return renewed;
    }

    public boolean release(InboxTaskClaim claim) {
        Objects.requireNonNull(claim);
        boolean released=Transactions.inTransaction(dataSource,c-> {
            registry(c,claim.task(),true); Row row=read(c,claim.task());
            if(!matches(row,claim)) return false;
            try(var s=c.prepareStatement("update inbox.task_claim set state='RELEASED' where tenant=? and instance_id=? and task_digest=?")) {
                key(s,claim.task()); s.executeUpdate(); return true;
            }
        });
        record(claim.task(),claim.owner(),claim.epoch(),released?"event.task.released":"event.task.release.refused"); return released;
    }

    /** Never opens/commits an independent transaction: the row fence is held until the real SDK checkpoint commits. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void requireCurrent(InboxTaskClaim claim) {
        Objects.requireNonNull(claim);
        try {
            if(transactions.getStatus()!=Status.STATUS_ACTIVE) throw new IllegalStateException("SDK checkpoint transaction required");
            try(var c=dataSource.getConnection()) {
                // An in-time dispatch reservation wins its wait deadline. Checkpointing still requires a live
                // lease and non-cancelled instance, but must not retroactively reject that accepted reservation.
                boolean active=registry(c,claim.task(),false,false); Row row=read(c,claim.task()); Instant now=now(c);
                if(!active || !matches(row,claim) || !row.expiresAt.isAfter(now))
                    throw new IllegalStateException("checkpoint lost task ownership");
            }
        } catch(SQLException | SystemException unavailable) { throw new StoreUnavailableException("task checkpoint fence unavailable",unavailable); }
    }

    private static boolean matches(Row row,InboxTaskClaim claim) {
        return row!=null && row.state.equals("ACTIVE") && row.epoch==claim.epoch() && row.owner.equals(claim.owner());
    }
    private static long ttlMillis(Duration ttl) {
        Objects.requireNonNull(ttl);
        if(ttl.compareTo(Duration.ofMillis(100))<0 || ttl.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("task lease TTL must be from 100ms through 30s");
        return ttl.toMillis();
    }
    private static boolean registry(Connection c,InboxTask task,boolean cleanup) throws SQLException {
        return registry(c,task,cleanup,true);
    }
    private static boolean registry(Connection c,InboxTask task,boolean cleanup,boolean requireUnexpiredWait) throws SQLException {
        try(var s=c.prepareStatement("select definition_id,definition_version,engine_instance_id,status,wait_deadline "
                +"from task.instance where tenant=? and id=? for update")) {
            s.setString(1,task.tenant()); s.setString(2,task.instanceId().value());
            try(var r=s.executeQuery()) {
                if(!r.next() || !(task.namespace()+"/"+task.definition()).equals(r.getString(1))
                        || !task.version().equals(r.getString(2)) || !task.engineInstanceId().equals(r.getString(3)))
                    throw new IllegalStateException("task lease does not identify the pinned instance");
                String status=r.getString(4); Timestamp deadline=r.getTimestamp(5);
                return cleanup || (Set.of("WAITING","RUNNING").contains(status)
                        && (!requireUnexpiredWait || deadline==null || deadline.toInstant().isAfter(now(c))));
            }
        }
    }
    private static Row read(Connection c,InboxTask task) throws SQLException {
        try(var s=c.prepareStatement("select owner,epoch,state,expires_at,application_id,engine_instance_id,namespace,definition,version,task_position,iteration "
                +"from inbox.task_claim where tenant=? and instance_id=? and task_digest=? for update")) {
            key(s,task);
            try(var r=s.executeQuery()) {
                if(!r.next()) return null;
                var actual=new InboxTask(task.tenant(),task.instanceId(),r.getString(5),r.getString(6),r.getString(7),r.getString(8),
                        r.getString(9),r.getString(10),r.getInt(11));
                if(!actual.equals(task)) throw new IllegalStateException("task lease identity changed");
                return new Row(r.getString(1),r.getLong(2),r.getString(3),r.getTimestamp(4).toInstant());
            }
        }
    }
    private static Instant now(Connection c) throws SQLException {
        try(var s=c.prepareStatement("select clock_timestamp()"); var r=s.executeQuery()) { r.next(); return r.getTimestamp(1).toInstant(); }
    }
    private static void key(PreparedStatement s,InboxTask task) throws SQLException { key(s,task,1); }
    private static void key(PreparedStatement s,InboxTask task,int offset) throws SQLException {
        s.setString(offset,task.tenant()); s.setString(offset+1,task.instanceId().value()); s.setString(offset+2,task.digest());
    }
    private void record(InboxTask task,String owner,long epoch,String action) {
        audit.record(AuditDraft.of(UlidGenerator.shared().next(),clock.instant(),RECOVERY,action,"event-task",task.digest(),
                Map.of(AuditKeys.TENANT,task.tenant(),AuditKeys.INSTANCE,task.instanceId().value(),"event.task",task.digest(),
                        "event.claim.owner",owner,"event.claim.epoch",Long.toString(epoch))));
    }
}
