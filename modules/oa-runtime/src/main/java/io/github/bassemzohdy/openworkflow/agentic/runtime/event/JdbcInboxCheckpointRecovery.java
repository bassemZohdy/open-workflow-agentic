package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;

/** Historical actual-checkpoint ACK repair. Neither a callback return nor this repair grants dispatch/resume authority. */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcInboxCheckpointRecovery {
    private static final Subject RECOVERY=new Subject(SubjectKind.SERVICE,"system:event-recovery");
    private static final String FACTS="""
            select p.wait_key,p.identity_digest,p.receipt_fingerprint,p.dispatch_attempt,p.application_id,
              p.engine_instance_id,p.task_position,p.iteration,p.task_digest,p.claim_owner,p.claim_epoch,
              d.state,d.identity_digest as delivery_digest,d.attempts,d.task_digest as delivery_task,
              d.claim_owner as delivery_owner,d.claim_epoch as delivery_epoch,
              m.tenant as message_tenant,m.source,m.event_id,m.type,m.instance_id as message_instance,
              m.correlation_key,m.data_json,m.fingerprint as stored_fingerprint
            from inbox.checkpoint_receipt p join inbox.delivery d using(tenant,instance_id,wait_key)
            left join inbox.signal s using(tenant,instance_id,wait_key)
            left join inbox.message m on m.tenant=s.tenant and m.source=s.source and m.event_id=s.event_id
            where p.tenant=? and p.instance_id=?
            """;
    private final DataSource dataSource;
    private final AuditSink audit;
    private final Clock clock;
    private record Receipt(String key,String digest,String fingerprint,int attempt,InboxTask task,String owner,Long epoch) { }
    private record Observed(Receipt receipt,boolean acknowledged) { }

    @Inject public JdbcInboxCheckpointRecovery(DataSource dataSource,AuditTrail audit) { this(dataSource,audit,Clock.systemUTC()); }
    public JdbcInboxCheckpointRecovery(DataSource dataSource,AuditSink audit,Clock clock) {
        this.dataSource=Objects.requireNonNull(dataSource);this.audit=Objects.requireNonNull(audit);this.clock=Objects.requireNonNull(clock);
    }

    /**
     * At most limit ACKs; invoke again on a later pass if false. Already-ACKed rows need no repeated audit. Only
     * immutable actual proof participates; an uncheckpointed reservation stays available to the future replay path.
     */
    public boolean repairBatch(InstanceRecord expected,String applicationId,int limit) {
        Objects.requireNonNull(expected);Objects.requireNonNull(expected.engineInstanceId(),"engineInstanceId");
        Checks.requireText("applicationId",applicationId,256);
        if(limit<1 || limit>128) throw new IllegalArgumentException("receipt repair batch must contain 1..128 entries");
        List<Receipt> pending=Transactions.inTransaction(dataSource,c->{
            lockRegistry(c,expected);
            var found=new ArrayList<Receipt>();
            try(var s=statement(c,FACTS+" and d.state is distinct from 'ACKNOWLEDGED' order by p.wait_key limit ? for update of d")) {
                key(s,expected);s.setInt(3,limit);
                try(var r=s.executeQuery()) { while(r.next()) found.add(read(r,expected,applicationId).receipt()); }
            }
            return List.copyOf(found);
        });
        for(var receipt:pending) {
            record(receipt); // No transaction/connection spans the independently committed audit.
            Transactions.inTransaction(dataSource,c->{
                lockRegistry(c,expected);
                try(var s=statement(c,FACTS+" and p.wait_key=? for update of d")) {
                    key(s,expected);s.setString(3,receipt.key());
                    try(var r=s.executeQuery()) {
                        if(!r.next()) throw new IllegalStateException("committed receipt disappeared during repair");
                        var current=read(r,expected,applicationId);
                        if(!current.receipt().equals(receipt)) throw new IllegalStateException("committed receipt changed during repair");
                        if(current.acknowledged()) return null;
                    }
                }
                try(var s=statement(c,"update inbox.delivery set state='ACKNOWLEDGED',acknowledged_at=clock_timestamp() "
                        +"where tenant=? and instance_id=? and wait_key=?")) {
                    key(s,expected);s.setString(3,receipt.key());
                    if(s.executeUpdate()!=1) throw new IllegalStateException("receipt repair lost its delivery row");
                }
                return null;
            });
        }
        return Transactions.inTransaction(dataSource,c->{
            lockRegistry(c,expected);
            try(var s=statement(c,"select exists(select 1 from inbox.checkpoint_receipt p join inbox.delivery d "
                    +"using(tenant,instance_id,wait_key) where p.tenant=? and p.instance_id=? and d.state<>'ACKNOWLEDGED')")) {
                key(s,expected);try(var r=s.executeQuery()) { r.next();return !r.getBoolean(1); }
            }
        });
    }

    private static Observed read(ResultSet r,InstanceRecord expected,String applicationId) throws SQLException {
        String state=r.getString("state");
        if(!Set.of("DISPATCHED","ACKNOWLEDGED").contains(state)
                || !applicationId.equals(r.getString("application_id")) || !expected.engineInstanceId().equals(r.getString("engine_instance_id"))
                || !r.getString("identity_digest").equals(r.getString("delivery_digest")) || r.getInt("dispatch_attempt")!=r.getInt("attempts"))
            throw new IllegalStateException("committed receipt conflicts with its pinned delivery");
        var message=new InboxMessage(r.getString("message_tenant"),r.getString("source"),r.getString("event_id"),r.getString("type"),
                Ulid.parse(r.getString("message_instance")),r.getString("correlation_key"),r.getString("data_json"));
        if(!expected.tenant().equals(message.tenant()) || !expected.id().equals(message.instanceId())
                || !message.fingerprint().equals(r.getString("receipt_fingerprint")) || !message.fingerprint().equals(r.getString("stored_fingerprint")))
            throw new IllegalStateException("committed receipt does not identify the retained message");
        var task=new InboxTask(expected.tenant(),expected.id(),applicationId,expected.engineInstanceId(),expected.definition().namespace(),
                expected.definition().name(),expected.definition().version(),r.getString("task_position"),r.getInt("iteration"));
        String digest=r.getString("task_digest"),owner=r.getString("claim_owner");Long epoch=(Long)r.getObject("claim_epoch");
        // Historical V67 proof may have no ownership fields. It remains consumption proof, never an ownership grant.
        if(digest==null) {
            if(owner!=null || epoch!=null) throw new IllegalStateException("historical receipt task attribution changed");
        } else {
            if(!task.digest().equals(digest) || owner==null || epoch==null)
                throw new IllegalStateException("committed receipt task attribution changed");
            new InboxTaskClaim(task,owner,epoch); // Validate the retained attribution, without granting current ownership.
        }
        if(!Objects.equals(digest,r.getString("delivery_task")) || !Objects.equals(owner,r.getString("delivery_owner"))
                || !Objects.equals(epoch,r.getObject("delivery_epoch")))
            throw new IllegalStateException("committed receipt ownership attribution changed");
        return new Observed(new Receipt(r.getString("wait_key"),r.getString("identity_digest"),message.fingerprint(),
                r.getInt("dispatch_attempt"),task,owner,epoch),state.equals("ACKNOWLEDGED"));
    }

    private static void lockRegistry(Connection c,InstanceRecord expected) throws SQLException {
        try(var s=statement(c,"select definition_id,definition_version,engine_instance_id,owner_kind,owner_id "
                +"from task.instance where tenant=? and id=? for update")) {
            key(s,expected);try(var r=s.executeQuery()) {
                if(!r.next() || !expected.definition().definitionId().equals(r.getString(1)) || !expected.definition().version().equals(r.getString(2))
                        || !expected.engineInstanceId().equals(r.getString(3)) || !expected.owner().kind().name().equals(r.getString(4))
                        || !expected.owner().id().equals(r.getString(5)))
                    throw new IllegalStateException("receipt recovery does not identify the pinned registry instance");
            }
        }
    }
    private static PreparedStatement statement(Connection c,String sql) throws SQLException {
        var s=c.prepareStatement(sql);s.setQueryTimeout(5);return s;
    }
    private static void key(PreparedStatement s,InstanceRecord row) throws SQLException { s.setString(1,row.tenant());s.setString(2,row.id().value()); }
    private void record(Receipt receipt) {
        var values=new LinkedHashMap<String,String>();
        values.put(AuditKeys.TENANT,receipt.task().tenant());values.put(AuditKeys.INSTANCE,receipt.task().instanceId().value());
        values.put("event.wait",receipt.key());values.put("event.receipt",receipt.fingerprint());
        values.put("event.attempt",Integer.toString(receipt.attempt()));values.put("event.repair","before-restore");
        audit.record(AuditDraft.of(UlidGenerator.shared().next(),clock.instant(),RECOVERY,"event.checkpoint.observed",
                "event-delivery",receipt.digest(),values));
    }
}
