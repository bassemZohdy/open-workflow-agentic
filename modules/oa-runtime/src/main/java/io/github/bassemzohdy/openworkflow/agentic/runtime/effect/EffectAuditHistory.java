package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Opt-in observed committed-history DATA, never permission, authenticity, a target callback or a default bean.
 * Each read observes one consistent database snapshot; in-flight/later commits are not covered. More than 128
 * facts refuses permanently until a separately reviewed larger-history protocol exists. No internal retry,
 * global watermark, cross-key compensation ordering or whole-operation deadline is supplied.
 */
public final class EffectAuditHistory {
    private static final String KEY = "thread_name=? and node_id=? and idempotency_key=?";
    private final DataSource data;
    private final TransactionManager manager;
    private final EffectAuditProducer producer;
    private final EffectAuditDelivery delivery;
    private record Entry(EffectAuditProducer.Retained fact, Instant ackedAt, AuditReceipt binding) { }
    private record Snapshot(List<Entry> entries) { }

    public EffectAuditHistory(DataSource data, TransactionManager manager, EffectAuditProducer producer,
                              EffectAuditDelivery delivery) {
        this.data=Objects.requireNonNull(data); this.manager=Objects.requireNonNull(manager);
        this.producer=Objects.requireNonNull(producer); this.delivery=Objects.requireNonNull(delivery);
    }

    /**
     * Re-read EVERY source through the trusted consumer, including already ACKed sources. Return an immutable
     * list only if the final observed history is unchanged and each exact central receipt is locally ACKed.
     * Partial ACKs survive refusal/cancellation and an explicit retry uses the same original sources. A caller
     * must independently establish current authority and freshness after this blocking work before any target.
     */
    public List<AuditReceipt> observedHistory(EffectScope scope, EffectKey key, int limit) {
        Objects.requireNonNull(scope); Objects.requireNonNull(key); scope.requireKey(key);
        if(limit<1 || limit>128)throw new IllegalArgumentException("history limit must be between 1 and 128");
        cancelled();
        return isolated(()->{
            var before=snapshot(scope,key,limit);
            var receipts=new LinkedHashMap<String,AuditReceipt>();
            for(var entry:before.entries()) {
                cancelled();
                var receipt=delivery.deliver(entry.fact().source().sequence());
                receipt.requireMatches(entry.fact().source(),entry.fact().draft());
                receipts.put(entry.fact().source().key(),receipt);
                cancelled();
            }
            var after=snapshot(scope,key,limit);
            if(!before.entries().stream().map(Entry::fact).toList().equals(after.entries().stream().map(Entry::fact).toList()))
                throw inconsistent();
            for(int i=0;i<after.entries().size();i++) {
                var old=before.entries().get(i); var current=after.entries().get(i);
                if(current.binding()==null || !current.binding().equals(receipts.get(current.fact().source().key())))
                    throw inconsistent();
                if(old.binding()!=null && (!old.binding().equals(current.binding()) || !old.ackedAt().equals(current.ackedAt())))
                    throw inconsistent();
            }
            cancelled();
            return List.copyOf(receipts.values());
        });
    }

    private Snapshot snapshot(EffectScope scope,EffectKey key,int limit) {
        return Transactions.inTransaction(data,c->{
            // Preserve SERIALIZABLE; otherwise use a repeatable read snapshot without leaking pool settings.
            Transactions.beginReadOnlySnapshot(c,5);
            String ledgerState, purpose;
            try(var q=c.prepareStatement("select state,purpose from effect.ledger where "+KEY)) {
                bind(q,1,key);try(var r=q.executeQuery()){if(!r.next())throw inconsistent();ledgerState=r.getString(1);purpose=r.getString(2);}
            }
            var bound=producer.bound(c,key).orElseThrow(EffectAuditHistory::inconsistent);
            if(!bound.scope().equals(scope))throw inconsistent();
            var initial=producer.find(c,bound.initialId()).orElseThrow(EffectAuditHistory::inconsistent);
            if(initial.source().transition()!=AuditSource.Transition.INTENT)throw inconsistent();
            List<Long> events;
            try(var q=c.prepareStatement("select id from effect.event where "+KEY+" order by id limit ?")) {
                int next=bind(q,1,key);q.setInt(next,limit+1);events=ids(q,limit);
            }
            if(events.isEmpty() || events.getFirst()!=bound.initialId())throw inconsistent();
            List<Long> sources;
            try(var q=c.prepareStatement("""
                select a.source_event_id from effect.audit_source a
                left join effect.event e on e.id=a.source_event_id
                left join effect.audit_scope s on s.initial_event_id=a.scope_initial_event_id
                where (e.thread_name=? and e.node_id=? and e.idempotency_key=?)
                   or (s.thread_name=? and s.node_id=? and s.idempotency_key=?)
                   or a.scope_initial_event_id=? or a.key_fingerprint=? order by a.source_event_id limit ?
                """)) {
                int n=bind(q,bind(q,1,key),key);q.setLong(n++,bound.initialId());
                q.setString(n++,initial.source().keyFingerprint());q.setInt(n,limit+1);sources=ids(q,limit);
            }
            List<Long> queues;
            try(var q=c.prepareStatement("""
                select w.source_event_id from effect.audit_delivery w
                left join effect.audit_source a on a.source_event_id=w.source_event_id
                left join effect.event e on e.id=w.source_event_id
                left join effect.audit_scope s on s.initial_event_id=a.scope_initial_event_id
                where (w.thread_name=? and w.node_id=? and w.idempotency_key=?)
                   or (e.thread_name=? and e.node_id=? and e.idempotency_key=?)
                   or (s.thread_name=? and s.node_id=? and s.idempotency_key=?)
                   or a.scope_initial_event_id=? or a.key_fingerprint=? order by w.source_event_id limit ?
                """)) {
                int n=bind(q,bind(q,bind(q,1,key),key),key);q.setLong(n++,bound.initialId());
                q.setString(n++,initial.source().keyFingerprint());q.setInt(n,limit+1);queues=ids(q,limit);
            }
            // Defense in depth against restored/corrupt stores with privileged trigger bypass, not authenticity.
            if(!events.equals(sources) || !events.equals(queues))throw inconsistent();
            var entries=new ArrayList<Entry>(); String prior="NONE";
            for(long id:events) {
                var fact=producer.find(c,id).orElseThrow(EffectAuditHistory::inconsistent);
                var attrs=fact.draft().attributes();
                if(!fact.source().scopeFingerprint().equals(initial.source().scopeFingerprint())
                        || !fact.source().keyFingerprint().equals(initial.source().keyFingerprint())
                        || !prior.equals(attrs.get("effect.from")) || !purpose.equals(attrs.get("effect.purpose")))throw inconsistent();
                prior=attrs.get("effect.to"); entries.add(acknowledgment(c,key,fact));
            }
            if(!ledgerState.equals(prior))throw inconsistent();
            return new Snapshot(List.copyOf(entries));
        });
    }

    private Entry acknowledgment(Connection c,EffectKey key,EffectAuditProducer.Retained fact) throws SQLException {
        try(var q=c.prepareStatement("""
            select w.thread_name,w.node_id,w.idempotency_key,w.ack_source_key,w.acked_at,
                   b.event_id,b.chain_id,b.chain_sequence,b.hash,b.content_fingerprint
            from effect.audit_delivery w left join audit.source_binding b on b.source_key=w.ack_source_key
            where w.source_event_id=?
            """)) {
            q.setLong(1,fact.source().sequence());try(var r=q.executeQuery()) {
                if(!r.next() || !key.threadId().value().equals(r.getString(1)) || !key.nodeId().equals(r.getString(2))
                        || !key.idempotencyKey().equals(r.getString(3)))throw inconsistent();
                String ack=r.getString(4);var time=r.getTimestamp(5);
                if(ack==null) {if(time!=null)throw inconsistent();return new Entry(fact,null,null);}
                if(!ack.equals(fact.source().key()) || time==null || r.getString(6)==null)throw inconsistent();
                var receipt=new AuditReceipt(fact.source(),Ulid.parse(r.getString(6)),r.getString(7),r.getLong(8),r.getString(9),r.getString(10));
                receipt.requireMatches(fact.source(),fact.draft());return new Entry(fact,time.toInstant(),receipt);
            }
        }
    }
    private static List<Long> ids(PreparedStatement q,int limit) throws SQLException {
        var result=new ArrayList<Long>();try(var r=q.executeQuery()){while(r.next())result.add(r.getLong(1));}
        if(result.size()>limit)throw inconsistent();return List.copyOf(result);
    }
    private static int bind(PreparedStatement q,int n,EffectKey key) throws SQLException {
        q.setString(n++,key.threadId().value());q.setString(n++,key.nodeId());q.setString(n++,key.idempotencyKey());return n;
    }
    private static void cancelled() {if(Thread.currentThread().isInterrupted())throw new CancellationException("effect audit history interrupted");}
    private static IllegalStateException inconsistent() {return new IllegalStateException("observed effect audit history is missing, changed or exceeds its bound");}
    private <T>T isolated(Supplier<T> action) {
        Transaction caller;
        try{caller=manager.suspend();}catch(Exception e){throw new StoreUnavailableException("caller transaction cannot be suspended",e);}
        Throwable primary=null;
        try{return action.get();}catch(RuntimeException|Error e){primary=e;throw e;}
        finally{if(caller!=null)try{manager.resume(caller);}catch(Exception e){
            if(primary==null)throw new StoreUnavailableException("caller transaction cannot be resumed",e);
            if(primary!=e)primary.addSuppressed(e);
        }}
    }
}
