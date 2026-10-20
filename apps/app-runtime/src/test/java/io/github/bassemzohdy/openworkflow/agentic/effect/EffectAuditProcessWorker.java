package io.github.bassemzohdy.openworkflow.agentic.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.IdempotentAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import jakarta.transaction.Status;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** Test-only worker: no target, model, Flow or real appendOnce consumer. halt skips rollback/finally and all replies. */
public final class EffectAuditProcessWorker {
    enum Mode {
        SOURCE_BEFORE_COMMIT(91,1,false),SOURCE_AFTER_COMMIT(92,1,true),
        ACK_BEFORE_COMMIT(93,2,false),ACK_AFTER_COMMIT(94,2,true),PREPARE(0,0,false),ACK(0,0,false);
        final int exit,commit;final boolean after;
        Mode(int exit,int commit,boolean after){this.exit=exit;this.commit=commit;this.after=after;}
    }
    static EffectCommand command(EffectScope scope){return scope.command("charge","process-item/é/1","approved-charge","{\"amount\":10}");}
    public static void main(String[] args) throws Exception {
        var manager=com.arjuna.ats.jta.TransactionManager.transactionManager();
        if(manager.getStatus()!=Status.STATUS_NO_TRANSACTION || manager.suspend()!=null)throw new IllegalStateException("unexpected ambient transaction");
        if(args.length==1 && args[0].equals("--self-check")){System.out.println("standalone real transaction manager ready");return;}
        if(args.length!=4)throw new IllegalArgumentException("database/tenant/instance/mode required");
        var mode=Mode.valueOf(args[3]);var base=EffectAuditTestDatabase.dataSource(args[0]);
        EffectAuditTestDatabase.requireOwnedDatabase(args[0],base);
        var row=new JdbcInstanceStore(base).find(args[1],Ulid.parse(args[2])).orElseThrow();
        var scope=new EffectScope(row.tenant(),row.id(),row.owner());var command=command(scope);var producer=new EffectAuditProducer();
        var ds=mode.commit==0?base:halting(base,mode);
        if(mode==Mode.ACK || mode.name().startsWith("ACK_")) {
            var sources=Transactions.inTransaction(base,c->producer.forKey(c,command.key(),0,2));
            if(sources.size()!=1)throw new IllegalStateException("one exact retained source required");
            // READ-ONLY central DATA-fixture adapter. Never appends or substitutes for the real atomic consumer.
            IdempotentAuditSink readback=(s,d)->fixtureReceipt(base,s,d);
            new EffectAuditDelivery(ds,manager,producer,readback).deliver(sources.getFirst().source().sequence());
            System.out.println("same-source acknowledgment reopened");
        } else {
            new JdbcEffectLedger(ds,manager,producer).prepareScoped(scope,command,Duration.ZERO,Instant.now().truncatedTo(ChronoUnit.MICROS));
            System.out.println("same-source intent reopened");
        }
        if(mode.exit!=0)throw new IllegalStateException("configured commit halt was not reached");
        if(manager.getStatus()!=Status.STATUS_NO_TRANSACTION)throw new IllegalStateException("caller transaction leaked");
    }
    static AuditReceipt fixtureReceipt(DataSource ds,AuditSource expected,AuditEvent draft) {
        return Transactions.inTransaction(ds,c->{
            try(var q=c.prepareStatement("select * from audit.source_binding where source_key=?")) {
                q.setString(1,expected.key());try(var r=q.executeQuery()) {
                    if(!r.next())throw new IllegalStateException("central DATA fixture missing");
                    var source=new AuditSource(r.getInt("schema_version"),r.getString("scope_fingerprint"),r.getString("key_fingerprint"),
                            AuditSource.Transition.valueOf(r.getString("transition")),r.getLong("source_sequence"),r.getString("content_fingerprint"));
                    var receipt=new AuditReceipt(source,Ulid.parse(r.getString("event_id")),r.getString("chain_id"),r.getLong("chain_sequence"),r.getString("hash"),r.getString("content_fingerprint"));
                    receipt.requireMatches(expected,draft);return receipt;
                }
            }
        });
    }
    private static DataSource halting(DataSource delegate,Mode mode) {
        var commits=new AtomicInteger();
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value;try{value=m.invoke(delegate,a);}catch(InvocationTargetException failed){throw failed.getCause();}
            if(!m.getName().equals("getConnection"))return value;var connection=(Connection)value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(q,cm,ca)->{
                boolean chosen=cm.getName().equals("commit") && commits.incrementAndGet()==mode.commit;
                if(chosen && !mode.after)halt(mode);
                Object result;try{result=cm.invoke(connection,ca);}catch(InvocationTargetException failed){throw failed.getCause();}
                if(chosen)halt(mode);
                return result;
            });
        });
    }
    private static void halt(Mode mode) {
        System.out.println("owned audit worker halt "+mode.name());System.out.flush();Runtime.getRuntime().halt(mode.exit);
    }
}
