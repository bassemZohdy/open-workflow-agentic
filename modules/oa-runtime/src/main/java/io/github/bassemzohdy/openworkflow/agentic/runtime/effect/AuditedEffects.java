package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.*;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Explicit opt-in audited effect boundary; no CDI/Flow/adapter activation. Uses the same core effect/compensation
 * protocol and keys, adding mandatory complete retained-source delivery before lookup/dispatch and private result.
 * Actual central appendOnce/chain validation and the private composite signed-identity/PDP/task/target/lease fence
 * are owned dependencies, NOT supplied here. A callback/tuple/ACK alone never grants production authority.
 * Session claim connections remain held across I/O (existing pool/session-pooling constraints apply); short audit
 * transactions close before central callbacks. No new transaction spans the target. Caller JTA is suspended across
 * every hook and cleanup. Unknown target outcomes retain the original key, lookup-first/bounded replay contract.
 */
public final class AuditedEffects {
    private final DataSource data;
    private final TransactionManager manager;
    private final EffectAuditProducer producer=new EffectAuditProducer();
    private final JdbcEffectLedger ledger;
    private final EffectAuditDelivery delivery;
    private final DurableEffects effects;

    public AuditedEffects(DataSource data,TransactionManager manager,IdempotentAuditSink sink,EffectTelemetry telemetry) {
        this.data=Objects.requireNonNull(data);this.manager=Objects.requireNonNull(manager);
        ledger=new JdbcEffectLedger(data,manager,producer);
        Objects.requireNonNull(sink);
        delivery=new EffectAuditDelivery(data,manager,producer,(source,draft)->{
            interrupted();var receipt=sink.appendOnce(source,draft);interrupted();return receipt;
        });
        effects=new DurableEffects(ledger,Clock.systemUTC(),Objects.requireNonNull(telemetry));
    }
    public Effect invoke(EffectInvocation invocation,EffectTarget target,EffectExecutionFence current) {
        Objects.requireNonNull(invocation);Objects.requireNonNull(target);Objects.requireNonNull(current);
        return suspended(()->{
            var boundary=new Guard(invocation,current);
            return invocation.compensationFor()==null?effects.execute(invocation.command(),target,boundary):
                    effects.compensate(invocation.compensationFor(),invocation.command(),target,boundary);
        });
    }
    private final class Guard implements DurableEffects.Boundary {
        private final EffectInvocation invocation;
        private final EffectExecutionFence current;
        Guard(EffectInvocation invocation,EffectExecutionFence current){this.invocation=invocation;this.current=current;}
        @Override public JdbcEffectLedger.Prepared prepare(EffectCommand command,Duration window,Instant now) {
            requireCurrent(EffectExecutionFence.Boundary.PREPARE,false);
            return ledger.prepareScoped(invocation.scope(),command,window,now);
        }
        @Override public void beforeCompensation(EffectKey original) {
            delivery.synchronize(invocation.scope(),original);
            confirmedCompensation(original).ifPresent(key->{
                if(!key.equals(invocation.command().key()))throw new IllegalStateException("different retained compensation");
            });
            requireCurrent(EffectExecutionFence.Boundary.PREPARE,false);
        }
        @Override public void beforeTarget(EffectCommand command,boolean lookup) {
            if(!command.equals(invocation.command()))throw new IllegalStateException("effect invocation changed");
            delivery.synchronize(invocation.scope(),command.key());
            requireCurrent(lookup?EffectExecutionFence.Boundary.LOOKUP:EffectExecutionFence.Boundary.DISPATCH,true);
        }
        @Override public void afterPrepared(EffectCommand command) {
            // Retain observed target facts before any interruption/current-authority/output refusal.
            delivery.synchronize(invocation.scope(),command.key());
        }
        @Override public void beforeReturn() {
            // After claim release and telemetry cleanup, including a resolved historical/cached return.
            delivery.synchronize(invocation.scope(),invocation.command().key());
            if(invocation.compensationFor()!=null)delivery.synchronize(invocation.scope(),invocation.compensationFor());
            var resultKey=invocation.compensationFor()==null?invocation.command().key():invocation.compensationFor();
            confirmedCompensation(resultKey).ifPresent(key->delivery.synchronize(invocation.scope(),key));
            requireCurrent(EffectExecutionFence.Boundary.RESULT,true);
        }
        private Optional<EffectKey> confirmedCompensation(EffectKey original) {
            return Transactions.inTransaction(data,c->{
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try(var q=c.prepareStatement("""
                    select o.state,x.compensation_node,x.compensation_key,u.state as undo_state,u.purpose,
                      s.initial_event_id,t.parent_initial_event_id
                    from effect.ledger o left join effect.compensation x using(thread_name,node_id,idempotency_key)
                    left join effect.ledger u on u.thread_name=x.thread_name and u.node_id=x.compensation_node and u.idempotency_key=x.compensation_key
                    left join effect.audit_scope s on s.thread_name=o.thread_name and s.node_id=o.node_id and s.idempotency_key=o.idempotency_key
                    left join effect.audit_scope t on t.thread_name=u.thread_name and t.node_id=u.node_id and t.idempotency_key=u.idempotency_key
                    where o.thread_name=? and o.node_id=? and o.idempotency_key=?
                    """)) {
                    q.setString(1,original.threadId().value());q.setString(2,original.nodeId());q.setString(3,original.idempotencyKey());
                    try(var r=q.executeQuery()) {
                        if(!r.next())throw new IllegalStateException("original effect missing");
                        if(!r.getString("state").equals("COMPENSATED"))return Optional.empty();
                        if(!"CONFIRMED".equals(r.getString("undo_state")) || !"COMPENSATION".equals(r.getString("purpose"))
                                || r.getObject("initial_event_id")==null || r.getObject("parent_initial_event_id")==null
                                || r.getLong("initial_event_id")!=r.getLong("parent_initial_event_id"))
                            throw new IllegalStateException("compensated effect lacks its confirmed retained compensation");
                        return Optional.of(new EffectKey(original.threadId(),r.getString("compensation_node"),r.getString("compensation_key")));
                    }
                }
            });
        }
        private void requireCurrent(EffectExecutionFence.Boundary boundary,boolean requireRetained) {
            interrupted();
            Transactions.inTransaction(data,c->{
                try(var limits=c.createStatement()){limits.execute("set local lock_timeout='2s'; set local statement_timeout='5s'");}
                producer.lockRoot(c,invocation.scope());
                if(requireRetained) {
                    var bound=producer.bound(c,invocation.command().key()).orElseThrow();
                    if(!bound.scope().equals(invocation.scope()))throw new IllegalStateException("effect full owner changed");
                    producer.find(c,bound.initialId()).orElseThrow();
                    try(var q=c.prepareStatement("select target,request_fingerprint,purpose from effect.ledger where thread_name=? and node_id=? and idempotency_key=?")) {
                        var key=invocation.command().key();q.setString(1,key.threadId().value());q.setString(2,key.nodeId());q.setString(3,key.idempotencyKey());
                        try(var r=q.executeQuery()) {
                            if(!r.next() || !invocation.command().target().equals(r.getString(1))
                                    || !invocation.command().requestFingerprint().equals(r.getString(2))
                                    || !(invocation.compensationFor()==null?"EFFECT":"COMPENSATION").equals(r.getString(3)))
                                throw new IllegalStateException("effect retained command differs");
                        }
                    }
                }
                return null;
            });
            interrupted();current.requireCurrent(invocation,boundary);interrupted(); // AFTER all blocking storage/audit.
        }
    }
    private <T> T suspended(Supplier<T> work) {
        Transaction prior;
        try{prior=manager.suspend();}catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be suspended",failed);}
        try{return work.get();}
        finally{if(prior!=null)try{manager.resume(prior);}catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be resumed",failed);}}
    }
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("audited effect interrupted; retain original facts");}
}
