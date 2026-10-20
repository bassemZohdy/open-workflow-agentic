package io.github.bassemzohdy.openworkflow.agentic.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectCommand;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.DurableEffects;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import java.sql.Connection;
import java.time.Duration;
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
        JdbcEffectLedger ledger = new JdbcEffectLedger(ds);
        DurableEffects effects = new DurableEffects(ledger);
        ThreadId thread = ThreadId.derive("effect-process-test", "alice", run);
        EffectCommand original = EffectCommand.of(thread, "charge", "line-1", "test:payments", "amount=10");
        EffectTarget target = new EffectTarget() {
            public String target() { return "test:payments"; }
            public Duration replayWindow() { return Duration.ofHours(1); }
            public Reply execute(String key) throws Exception {
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
        } else if (effects.execute(original, target).state() != EffectState.CONFIRMED) {
            throw new IllegalStateException("effect unresolved");
        }
    }
}
