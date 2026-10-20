package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
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
    private <T> T isolated(Supplier<T> action) {
        Transaction caller;
        try{caller=manager.suspend();}catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be suspended",failed);}
        try{return action.get();}
        finally{if(caller!=null)try{manager.resume(caller);}catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be resumed",failed);}}
    }
}
