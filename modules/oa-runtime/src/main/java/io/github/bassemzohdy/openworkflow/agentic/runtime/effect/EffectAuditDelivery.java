package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.IdempotentAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Explicit opt-in co-located source delivery, with no scheduler, CDI registration, target callback or dispatch
 * permission. Close every source connection before calling the trusted central appendOnce consumer. A missing
 * response retains pending work; known success still requires a matching stored central binding before local ACK.
 * Any lost ACK commit reply is read back by repeating the SAME source, never inventing a new event or permission.
 */
public final class EffectAuditDelivery {
    private static final String EARLIER="""
        exists(select 1 from effect.audit_delivery p where p.thread_name=w.thread_name and p.node_id=w.node_id
          and p.idempotency_key=w.idempotency_key and p.source_event_id<w.source_event_id and p.ack_source_key is null)
        """;
    private final DataSource dataSource;
    private final TransactionManager manager;
    private final EffectAuditProducer producer;
    private final IdempotentAuditSink sink;

    public EffectAuditDelivery(DataSource dataSource,TransactionManager manager,EffectAuditProducer producer,IdempotentAuditSink sink) {
        this.dataSource=Objects.requireNonNull(dataSource);this.manager=Objects.requireNonNull(manager);
        this.producer=Objects.requireNonNull(producer);this.sink=Objects.requireNonNull(sink);
    }

    /** One ready pending fact per key. No global committed watermark: delayed lower-id commits remain visible. */
    public List<Long> ready(int limit) {
        if(limit<1 || limit>128)throw new IllegalArgumentException("audit delivery batch must be between 1 and 128");
        return isolated(()->Transactions.inTransaction(dataSource,c->{
            var ids=new ArrayList<Long>();
            try(var q=c.prepareStatement("select w.source_event_id from effect.audit_delivery w where w.ack_source_key is null and not "+EARLIER+" order by w.source_event_id limit ?")) {
                q.setInt(1,limit);try(var r=q.executeQuery()){while(r.next())ids.add(r.getLong(1));}
            }
            return List.copyOf(ids);
        }));
    }

    /** Returns checked historical receipt DATA only. Repeated delivery always calls the trusted consumer readback. */
    public AuditReceipt deliver(long journalId) {
        if(journalId<1)throw new IllegalArgumentException("journal id must be positive");
        return isolated(()->{
            var retained=Transactions.inTransaction(dataSource,c->{
                try(var q=c.prepareStatement("select "+EARLIER+" from effect.audit_delivery w where w.source_event_id=?")) {
                    q.setLong(1,journalId);try(var r=q.executeQuery()) {
                        if(!r.next() || r.getBoolean(1))throw new IllegalStateException("audit delivery missing or earlier fact remains pending");
                    }
                }
                return producer.find(c,journalId).orElseThrow(()->new IllegalStateException("retained audit source missing"));
            });
            // No source connection/transaction survives across this dependency call, even with a one-slot pool.
            var receipt=Objects.requireNonNull(sink.appendOnce(retained.source(),retained.draft()),"audit receipt");
            receipt.requireMatches(retained.source(),retained.draft());
            Transactions.inTransaction(dataSource,c->{
                if(!retained.equals(producer.find(c,journalId).orElseThrow()))throw new IllegalStateException("retained audit source changed");
                try(var q=c.prepareStatement("""
                    select 1 from audit.source_binding where source_key=? and event_id=? and chain_id=?
                      and chain_sequence=? and hash=? and content_fingerprint=?
                    """)) {
                    q.setString(1,receipt.source().key());q.setString(2,receipt.eventId().value());q.setString(3,receipt.chainId());
                    q.setLong(4,receipt.chainSequence());q.setString(5,receipt.hash());q.setString(6,receipt.contentFingerprint());
                    try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("audit receipt has no matching stored central binding");}
                }
                try(var q=c.prepareStatement("update effect.audit_delivery set ack_source_key=?,acked_at=clock_timestamp() where source_event_id=? and ack_source_key is null")) {
                    q.setString(1,receipt.source().key());q.setLong(2,journalId);q.executeUpdate();
                }
                try(var q=c.prepareStatement("select ack_source_key,acked_at from effect.audit_delivery where source_event_id=?")) {
                    q.setLong(1,journalId);try(var r=q.executeQuery()) {
                        if(!r.next() || !receipt.source().key().equals(r.getString(1)) || r.getTimestamp(2)==null)
                            throw new IllegalStateException("audit delivery acknowledgment missing or changed");
                    }
                }
                return null;
            });
            return receipt;
        });
    }

    /** One bounded pass, no internal retry loop. Failure preserves unacknowledged work and stops the pass. */
    public List<AuditReceipt> drain(int limit) {
        var receipts=new ArrayList<AuditReceipt>();for(long id:ready(limit))receipts.add(deliver(id));return List.copyOf(receipts);
    }

    /**
     * One complete bounded key history, not a page that can hide an unacknowledged tail. Every retained source,
     * including an already ACKed source, goes through trusted consumer readback again. Missing source/queue,
     * wrong full scope, more than 128 facts, concurrent history growth or changed central binding refuses.
     * This internal barrier remains DATA/protocol verification, not chain authenticity or caller authority.
     */
    void synchronize(EffectScope scope,EffectKey key) {
        isolated(()->{
            var before=snapshot(scope,key,null);
            var receipts=new ArrayList<AuditReceipt>();
            for(var fact:before)receipts.add(deliver(fact.source().sequence()));
            if(!before.equals(snapshot(scope,key,receipts)))
                throw new IllegalStateException("effect history changed during mandatory audit");
            return null;
        });
    }
    private List<EffectAuditProducer.Retained> snapshot(EffectScope scope,EffectKey key,List<AuditReceipt> receipts) {
        scope.requireKey(key);
        return Transactions.inTransaction(dataSource,c->{
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try(var limits=c.createStatement()){limits.execute("set local lock_timeout='2s'; set local statement_timeout='5s'");}
            if(!producer.bound(c,key).orElseThrow().scope().equals(scope))
                throw new IllegalStateException("effect audit full scope differs");
            var facts=new ArrayList<EffectAuditProducer.Retained>();
            try(var q=c.prepareStatement("""
                select e.id,a.source_event_id as source_id,w.source_event_id as queue_id,
                  w.thread_name,w.node_id,w.idempotency_key,w.ack_source_key,w.acked_at,
                  b.event_id,b.chain_id,b.chain_sequence,b.hash,b.content_fingerprint
                from effect.event e left join effect.audit_source a on a.source_event_id=e.id
                left join effect.audit_delivery w on w.source_event_id=e.id
                left join audit.source_binding b on b.source_key=w.ack_source_key
                where e.thread_name=? and e.node_id=? and e.idempotency_key=? order by e.id limit 129
                """)) {
                q.setString(1,key.threadId().value());q.setString(2,key.nodeId());q.setString(3,key.idempotencyKey());
                try(var r=q.executeQuery()) {while(r.next()) {
                    if(facts.size()==128 || r.getObject("source_id")==null || r.getObject("queue_id")==null
                            || !key.threadId().value().equals(r.getString("thread_name"))
                            || !key.nodeId().equals(r.getString("node_id")) || !key.idempotencyKey().equals(r.getString("idempotency_key")))
                        throw new IllegalStateException("effect audit history is missing, changed or exceeds the bounded barrier");
                    var fact=producer.find(c,r.getLong("id")).orElseThrow();
                    if(receipts!=null) {
                        if(facts.size()>=receipts.size() || !fact.source().key().equals(r.getString("ack_source_key"))
                                || r.getTimestamp("acked_at")==null || r.getString("event_id")==null)
                            throw new IllegalStateException("effect audit acknowledgment missing or changed");
                        var stored=new AuditReceipt(fact.source(),Ulid.parse(r.getString("event_id")),r.getString("chain_id"),
                                r.getLong("chain_sequence"),r.getString("hash"),r.getString("content_fingerprint"));
                        stored.requireMatches(fact.source(),fact.draft());
                        if(!stored.equals(receipts.get(facts.size())))throw new IllegalStateException("effect audit receipt changed");
                    }
                    facts.add(fact);
                }}
            }
            if(facts.isEmpty() || (receipts!=null && receipts.size()!=facts.size()))
                throw new IllegalStateException("effect audit history is empty or changed");
            return List.copyOf(facts);
        });
    }
    private <T> T isolated(Supplier<T> action) {
        Transaction caller;
        try{caller=manager.suspend();}catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be suspended",failed);}
        try{return action.get();}
        finally{if(caller!=null)try{manager.resume(caller);}catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be resumed",failed);}}
    }
}
