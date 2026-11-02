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
            s.execute("create table " + TARGET_SCHEMA + ".test_attempt "
                    + "(run_id varchar(64) not null, effect_key varchar(200) not null)");
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

    @Test
    void auditedResourceEffectRecoversAfterProcessDiesAfterTargetCommit() throws Exception {
        String run=UUID.randomUUID().toString();var ds=EffectCrashWorker.dataSource();
        var owner=new io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject(
                io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind.USER,"alice");
        var store=new io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore(ds);
        var req=new io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest("scoped-"+run,owner,
                new io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef("agentic/effect-crash","1"),java.util.Map.of(),"start");
        var now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var row=store.insertIfAbsent(io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord.starting(
                io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator.shared().next(),req,now),"test.start").record();
        row=store.replace(row.successor(now,io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus.RUNNING,
                "engine-"+row.id(),1,null,null),row.version(),"test.running",owner);
        var scope=EffectCrashWorker.scope(run,row.id().value());
        try(var c=ds.getConnection();var q=c.prepareStatement("""
            insert into task.resource_call(call_key,fingerprint,tenant,instance_id,owner_kind,owner_id,application_id,
              engine_instance_id,namespace,definition_name,definition_version,task_position,iteration,logical_occurrence,
              audience,operation,target_fingerprint,request_fingerprint) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            q.setString(1,scope.key());q.setString(2,scope.fingerprint());q.setString(3,scope.tenant());q.setString(4,scope.instanceId().value());
            q.setString(5,scope.owner().kind().name());q.setString(6,scope.owner().id());q.setString(7,scope.applicationId());
            q.setString(8,scope.engineInstanceId());q.setString(9,scope.namespace());q.setString(10,scope.definition());
            q.setString(11,scope.version());q.setString(12,scope.taskPosition());q.setInt(13,scope.iteration());
            q.setString(14,scope.logicalOccurrence());q.setString(15,scope.audience());q.setString(16,scope.operation());
            q.setString(17,scope.targetFingerprint());q.setString(18,scope.requestFingerprint());q.executeUpdate();
        }
        var ledger=new JdbcEffectLedger(ds);var command=EffectCrashWorker.scopedCommand(scope);
        child(run,"scoped-crash",77,row.id().value());
        assertEquals(EffectState.INTENDED,ledger.find(command.key()).orElseThrow().state());
        assertEquals(1,targetEffects(run));assertEquals(2,audits(row.id().value()));
        child(run,"scoped-recover",0,row.id().value());
        assertEquals(EffectState.CONFIRMED,ledger.find(command.key()).orElseThrow().state());
        child(run,"scoped-recover",0,row.id().value());
        assertEquals(1,targetEffects(run));assertEquals(1,attempts(run));assertEquals(3,audits(row.id().value()));
    }
    private static long audits(String instance) throws Exception {
        try(var c=EffectCrashWorker.dataSource().getConnection();var q=c.prepareStatement(
                "select count(*) from audit.event where attributes->>'oa.instance'=? and event_type like 'effect.%'")) {
            q.setString(1,instance);try(var r=q.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    private static long attempts(String run) throws Exception {
        try(var c=EffectCrashWorker.dataSource().getConnection();var q=c.prepareStatement(
                "select count(*) from "+TARGET_SCHEMA+".test_attempt where run_id=?")) {
            q.setString(1,run);try(var r=q.executeQuery()){r.next();return r.getLong(1);}
        }
    }

    @Test
    void processRestartWithClockSkewDoesNotResendAfterOriginalWindow() throws Exception {
        String run = UUID.randomUUID().toString();
        ThreadId thread = ThreadId.derive("effect-process-test", "alice", run);
        EffectCommand original = EffectCommand.of(thread, "charge", "line-1", "test:payments", "amount=10");
        JdbcEffectLedger ledger = new JdbcEffectLedger(EffectCrashWorker.dataSource());
        child(run, "skew-crash", 77);
        assertEquals(EffectState.INTENDED, ledger.find(original.key()).orElseThrow().state());
        Thread.sleep(500); // The original 200 ms replay guarantee has expired on the database clock.
        child(run, "skew-recover", 0);
        assertEquals(EffectState.INTENDED, ledger.find(original.key()).orElseThrow().state());
        assertEquals(1, targetEffects(run));
        try (Connection c = EffectCrashWorker.dataSource().getConnection(); var s = c.prepareStatement(
                "select count(*) from " + TARGET_SCHEMA + ".test_attempt where run_id = ?")) {
            s.setString(1, run);
            try (var r = s.executeQuery()) { r.next(); assertEquals(1, r.getLong(1), "recovery must not resend"); }
        }
        assertEquals(1, ledger.manualQueue(thread, 10).size());
    }

    private static long targetEffects(String run) throws Exception {
        try (Connection c = EffectCrashWorker.dataSource().getConnection(); var s = c.prepareStatement(
                "select count(*) from " + TARGET_SCHEMA + ".test_target where run_id = ?")) {
            s.setString(1, run);
            try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    private static void child(String run, String mode, int exit) throws Exception {child(run,mode,exit,null);}
    private static void child(String run, String mode, int exit,String instance) throws Exception {
        Path log = Path.of("target", "process-logs", "effect-" + run + "-" + mode + ".log");
        Files.createDirectories(log.getParent());
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command=new java.util.ArrayList<String>(java.util.List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, EffectCrashWorker.class.getName(), run, mode, TARGET_SCHEMA));
        if(instance!=null)command.add(instance);
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
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
