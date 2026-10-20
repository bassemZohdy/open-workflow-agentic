package io.github.bassemzohdy.openworkflow.agentic.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectManualDecisions;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import jakarta.transaction.Status;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;

/** TEST ONLY abrupt process loss around trusted manual DATA persistence. No actual operator/PEP/evidence consumer. */
public final class EffectManualProcessWorker {
    enum Mode {
        AFTER_SOURCE(97,false),BEFORE_COMMIT(98,false),AFTER_COMMIT(99,true),RECORD(0,false),FIND(0,false);
        final int exit;final boolean committed;
        Mode(int exit,boolean committed){this.exit=exit;this.committed=committed;}
    }
    static EffectCommand command(EffectScope scope,boolean undo) {
        return scope.command(undo?"refund":"charge","manual-process/é/1",undo?"approved-manual-refund":"approved-manual-charge",undo?"amount=-10":"amount=10");
    }
    static EffectManualDecision decision(EffectScope scope,EffectCommand command,Ulid id,long expected,EffectState outcome) {
        // Explicit DATA fixture: these references do not prove policy authorization or authoritative evidence.
        return new EffectManualDecision(id,scope,command,expected,outcome,new Subject(SubjectKind.USER,"operator-process-𝄞"),
                new PolicyRef("fixture.process.resolve-effect","v1"),"a".repeat(64),"b".repeat(64));
    }
    public static void main(String[] args) throws Exception {
        var manager=com.arjuna.ats.jta.TransactionManager.transactionManager();
        if(manager.getStatus()!=Status.STATUS_NO_TRANSACTION || manager.suspend()!=null)throw new IllegalStateException("unexpected ambient transaction");
        if(args.length==1 && args[0].equals("--self-check")){System.out.println("standalone manual worker real transaction manager ready");return;}
        if(args.length!=8)throw new IllegalArgumentException("owned database/tenant/instance/decision/history/outcome/purpose/mode required");
        var base=EffectAuditTestDatabase.dataSource(args[0]);EffectAuditTestDatabase.requireOwnedDatabase(args[0],base);
        var row=new JdbcInstanceStore(base).find(args[1],Ulid.parse(args[2])).orElseThrow();
        var scope=new EffectScope(row.tenant(),row.id(),row.owner());
        if(!args[6].equals("ROOT") && !args[6].equals("UNDO"))throw new IllegalArgumentException("unknown fixture purpose");
        var command=command(scope,args[6].equals("UNDO"));var mode=Mode.valueOf(args[7]);
        var expected=decision(scope,command,Ulid.parse(args[3]),Long.parseLong(args[4]),EffectState.valueOf(args[5]));
        var store=new JdbcEffectManualDecisions(observed(base,mode),manager);
        var actual=mode==Mode.FIND?store.find(scope,command,expected.id()).orElseThrow():store.record(expected);
        if(!actual.decision().equals(expected))throw new IllegalStateException("original manual tuple changed");
        if(mode.exit!=0)throw new IllegalStateException("configured manual halt was not reached");
        if(manager.getStatus()!=Status.STATUS_NO_TRANSACTION)throw new IllegalStateException("caller transaction leaked");
        System.out.println(mode==Mode.FIND?"owned manual worker read SAME_DECISION":"owned manual worker record SAME_DECISION");
    }
    private static DataSource observed(DataSource delegate,Mode mode) {
        var sourceInserted=new AtomicBoolean();var commitChosen=new AtomicBoolean();
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value=call(m,delegate,a);if(!m.getName().equals("getConnection"))return value;var connection=(Connection)value;
            System.out.println("owned manual backend "+connection.unwrap(PGConnection.class).getBackendPID());System.out.flush();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                boolean chosen=cm.getName().equals("commit") && (mode==Mode.BEFORE_COMMIT || mode==Mode.AFTER_COMMIT) && commitChosen.compareAndSet(false,true);
                if(chosen && !sourceInserted.get())throw new IllegalStateException("manual source must precede chosen commit");
                if(chosen && mode==Mode.BEFORE_COMMIT)halt(mode);
                Object result=call(cm,connection,ca);
                if(chosen)halt(mode);
                if(!cm.getName().equals("prepareStatement") || ca==null || !(ca[0] instanceof String sql) || !sql.contains("insert into effect.audit_source("))return result;
                var statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->{
                    Object answer=call(sm,statement,sa);
                    if(sm.getName().equals("executeUpdate")) {
                        if(!Integer.valueOf(1).equals(answer))throw new IllegalStateException("one manual source required");
                        sourceInserted.set(true);if(mode==Mode.AFTER_SOURCE)halt(mode);
                    }
                    return answer;
                });
            });
        });
    }
    private static Object call(Method method,Object target,Object[] args) throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException failed){throw failed.getCause();}}
    private static void halt(Mode mode) {System.out.println("owned manual worker halt "+mode.name());System.out.flush();Runtime.getRuntime().halt(mode.exit);}
}
