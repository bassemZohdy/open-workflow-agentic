package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectCommand;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Real process death with durable PostgreSQL state and a separate target commit. Executed by failsafe in CI. */
class EffectCrashRecoveryIT {
    private static final String TARGET_SCHEMA = "effect_crash_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    static void targetTable() throws Exception {
        // Standalone execution must not depend on another boot test migrating first.
        org.flywaydb.core.Flyway.configure().dataSource(EffectCrashWorker.dataSource())
                .locations("classpath:db/migration").load().migrate();
        try (Connection c = EffectCrashWorker.dataSource().getConnection(); var s = c.createStatement()) {
            s.execute("create schema " + TARGET_SCHEMA);
            s.execute("create table " + TARGET_SCHEMA + ".test_target "
                    + "(run_id varchar(64) not null, effect_key varchar(200) primary key)");
        }
    }

    @AfterAll
    static void removeOwnTargetSchema() throws Exception {
        try (Connection c = EffectCrashWorker.dataSource().getConnection(); var s = c.createStatement()) {
            s.execute("drop schema if exists " + TARGET_SCHEMA + " cascade");
        }
    }

    @Test
    @Acceptance(value = "T03", requirements = {"M06", "M07"})
    void processDeathAfterTargetCommitDoesNotDuplicateEffectOrCompensation() throws Exception {
        String run = UUID.randomUUID().toString();
        ThreadId thread = ThreadId.derive("effect-process-test", "alice", run);
        EffectCommand original = EffectCommand.of(thread, "charge", "line-1", "test:payments", "amount=10");
        JdbcEffectLedger ledger = new JdbcEffectLedger(EffectCrashWorker.dataSource());
        child(run, "execute-crash", 77);
        assertEquals(EffectState.INTENDED, ledger.find(original.key()).orElseThrow().state());
        child(run, "recover", 0);
        assertEquals(EffectState.CONFIRMED, ledger.find(original.key()).orElseThrow().state());
        assertEquals(1, targetEffects(run));
        child(run, "compensate-crash", 77);
        assertEquals(EffectState.CONFIRMED, ledger.find(original.key()).orElseThrow().state(),
                "a lost compensation acknowledgment is not proof that compensation completed");
        child(run, "compensate-recover", 0);
        assertEquals(EffectState.COMPENSATED, ledger.find(original.key()).orElseThrow().state());
        child(run, "compensate-recover", 0);
        assertEquals(2, targetEffects(run), "one charge and one refund, even after process death and repeated recovery");
    }

    private static long targetEffects(String run) throws Exception {
        try (Connection c = EffectCrashWorker.dataSource().getConnection(); var s = c.prepareStatement(
                "select count(*) from " + TARGET_SCHEMA + ".test_target where run_id = ?")) {
            s.setString(1, run);
            try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    private static void child(String run, String mode, int exit) throws Exception {
        Path log = Path.of("target", "process-logs", "effect-" + run + "-" + mode + ".log");
        Files.createDirectories(log.getParent());
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, EffectCrashWorker.class.getName(), run, mode, TARGET_SCHEMA)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), () -> "child timed out: " + log);
            assertEquals(exit, child.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception e) { return log.toString(); }
            });
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
        }
    }
}
