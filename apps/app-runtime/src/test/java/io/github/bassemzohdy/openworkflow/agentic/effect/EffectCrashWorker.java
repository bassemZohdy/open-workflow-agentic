package io.github.bassemzohdy.openworkflow.agentic.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectCommand;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.DurableEffects;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import java.sql.Connection;
import java.time.Duration;
import java.time.Clock;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only child JVM. halting after the target commit skips all finally blocks and loses the acknowledgment. */
public class EffectCrashWorker {
    static PGSimpleDataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        return ds;
    }

    public static void main(String[] args) {
        String run = args[0], mode = args[1];
        String targetSchema = args[2];
        if (!targetSchema.matches("effect_crash_[0-9a-f]{32}")) throw new IllegalArgumentException("invalid test schema");
        PGSimpleDataSource ds = dataSource();
        if (mode.startsWith("scoped-")) {
            scoped(run,mode,targetSchema,args[3],ds);
            return;
        }
        JdbcEffectLedger ledger = new JdbcEffectLedger(ds);
        boolean skew = mode.startsWith("skew-");
        Clock clock = skew ? Clock.offset(Clock.systemUTC(), mode.endsWith("crash")
                ? Duration.ofHours(1) : Duration.ofHours(-1)) : Clock.systemUTC();
        DurableEffects effects = new DurableEffects(ledger, clock);
        ThreadId thread = ThreadId.derive("effect-process-test", "alice", run);
        EffectCommand original = EffectCommand.of(thread, "charge", "line-1", "test:payments", "amount=10");
        EffectTarget target = new EffectTarget() {
            public String target() { return "test:payments"; }
            public Duration replayWindow() {
                return skew && mode.endsWith("crash") ? Duration.ofMillis(200)
                        : Duration.ofHours(skew ? 2 : 1);
            }
            public Reply execute(String key) throws Exception {
                try (Connection c = ds.getConnection(); var s = c.prepareStatement(
                        "insert into " + targetSchema + ".test_attempt (run_id, effect_key) values (?, ?)")) {
                    s.setString(1, run); s.setString(2, key); s.executeUpdate();
                }
                try (Connection c = ds.getConnection(); var s = c.prepareStatement(
                        "insert into " + targetSchema + ".test_target (run_id, effect_key) values (?, ?) on conflict do nothing")) {
                    s.setString(1, run); s.setString(2, key); s.executeUpdate(); // Auto-commit in the target database.
                }
                if (mode.endsWith("crash")) Runtime.getRuntime().halt(77);
                return new Reply(Outcome.CONFIRMED, "target confirmed");
            }
        };
        if (mode.startsWith("compensate")) {
            EffectCommand undo = EffectCommand.of(thread, "refund", "line-1", target.target(), "refund=10");
            if (effects.compensate(original.key(), undo, target).state() != EffectState.COMPENSATED) {
                throw new IllegalStateException("compensation unresolved");
            }
        } else {
            EffectState expected = mode.equals("skew-recover") ? EffectState.INTENDED : EffectState.CONFIRMED;
            if (effects.execute(original, target).state() != expected) throw new IllegalStateException("unexpected effect state");
        }
    }
    static io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope scope(String run,String id) {
        var owner=new io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject(
                io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind.USER,"alice");
        return new io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope("scoped-"+run,
                io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(id),owner,"test-app","engine-"+id,
                "agentic","effect-crash","1","/do/0/tool",1,"root/charge","payments","charge",
                "a".repeat(64),"b".repeat(64));
    }
    static EffectCommand scopedCommand(io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope scope) {
        return EffectCommand.of(scope.threadId(),"tool:charge",scope.key(),"test:payments","amount=10");
    }
    private static void scoped(String run,String mode,String targetSchema,String id,PGSimpleDataSource ds) {
        var call=scope(run,id);var command=scopedCommand(call);
        var manager=(jakarta.transaction.TransactionManager)java.lang.reflect.Proxy.newProxyInstance(
                jakarta.transaction.TransactionManager.class.getClassLoader(),new Class<?>[]{jakarta.transaction.TransactionManager.class},
                (p,m,a)->{if(m.getName().equals("suspend"))return null;throw new UnsupportedOperationException(m.getName());});
        var producer=new io.github.bassemzohdy.openworkflow.agentic.runtime.effect.EffectAuditProducer();
        var ledger=new JdbcEffectLedger(ds,manager,producer);
        var audit=new io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce(ds);
        var delivery=new io.github.bassemzohdy.openworkflow.agentic.runtime.effect.EffectAuditDelivery(ds,manager,producer,audit::appendOnce);
        var history=new io.github.bassemzohdy.openworkflow.agentic.runtime.effect.EffectAuditHistory(ds,manager,producer,delivery);
        var target=new EffectTarget() {
            public String target(){return "test:payments";}
            public Duration replayWindow(){return Duration.ofHours(1);}
            public Reply execute(String key) throws Exception {
                try(var c=ds.getConnection();var q=c.prepareStatement("insert into "+targetSchema+".test_attempt(run_id,effect_key) values(?,?)")) {
                    q.setString(1,run);q.setString(2,key);q.executeUpdate();
                }
                try(var c=ds.getConnection();var q=c.prepareStatement("insert into "+targetSchema+".test_target(run_id,effect_key) values(?,?) on conflict do nothing")) {
                    q.setString(1,run);q.setString(2,key);q.executeUpdate();
                }
                if(mode.equals("scoped-crash"))Runtime.getRuntime().halt(77);
                return new Reply(Outcome.CONFIRMED,"confirmed");
            }
            public Reply lookup(String key) throws Exception {
                try(var c=ds.getConnection();var q=c.prepareStatement("select 1 from "+targetSchema+".test_target where run_id=? and effect_key=?")) {
                    q.setString(1,run);q.setString(2,key);try(var r=q.executeQuery()) {
                        return new Reply(r.next()?Outcome.CONFIRMED:Outcome.UNKNOWN,null);
                    }
                }
            }
        };
        if(new DurableEffects(ledger,Clock.systemUTC()).executeResourceScoped(call,command,target,history).state()!=EffectState.CONFIRMED)
            throw new IllegalStateException("scoped effect remained unresolved");
    }

}
