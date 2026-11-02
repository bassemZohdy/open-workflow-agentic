package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegisteredHumanWaitFenceTest {
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final String FILTER = "{\"with\":{\"source\":\"" + HumanDecisionWake.SOURCE
            + "\",\"type\":\"" + HumanDecisionWake.TYPE + "\"}}";
    private static final String SNAPSHOT = "{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"one\":" + FILTER + "}}}}";
    private static final String FILTER_HASH = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.event-filter.v1").text(FILTER).toBytes());
    private PGSimpleDataSource admin, owned;
    private String database;
    private boolean created;

    private static PGSimpleDataSource source(String name) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        if (name != null) ds.setDatabaseName(name);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        ds.setConnectTimeout(5); ds.setSocketTimeout(15); return ds;
    }
    @BeforeAll void setup() throws Exception {
        admin = source(null); database = "owa_hwait_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(database.matches("owa_hwait_[0-9a-f]{32}"));
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("create database " + database); created = true; }
        owned = source(database);
        org.flywaydb.core.Flyway.configure().dataSource(owned).locations("classpath:db/migration").load().migrate();
        for (String path : List.of("/human/proposed-human-decision.sql", "/human/proposed-registered-wait-fence.sql")) {
            try (var in = getClass().getResourceAsStream(path)) {
                assertNotNull(in); String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                try (var c = owned.getConnection(); var s = c.createStatement()) { s.execute(sql); }
            }
        }
    }
    @AfterAll void cleanup() throws Exception {
        if (!created) return;
        assertTrue(database.matches("owa_hwait_[0-9a-f]{32}"));
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("drop database " + database); }
    }
    private record Fixture(InboxWait registered, HumanDecision decision, CallerAuthority authority) { }
    private Fixture fixture(Duration duration) throws Exception {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var registry = new JdbcInstanceStore(owned);
        var start = new StartRequest("tenant", ALICE, new DefinitionRef("agentic/human-test", "1"), Map.of(), UUID.randomUUID().toString());
        var initial = registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), start, now), "test.start").record();
        String engine = "engine-" + initial.id().value();
        var running = registry.replace(initial.successor(now, InstanceStatus.RUNNING, engine, 1, null, null), initial.version(), "test.running", ALICE);
        registry.replace(running.successor(now, InstanceStatus.WAITING, engine, 1, null, null), running.version(), "test.waiting", ALICE);
        Ulid task = UlidGenerator.shared().next();
        var wait = new InboxWait("tenant", initial.id(), "app", engine, "agentic", "human-test", "1",
                "do/0/human", 1, HumanDecisionWake.route(task), HumanDecisionWake.TYPE, FILTER_HASH, duration);
        new JdbcInboxDispatch(owned, (AuditSink) event -> { }).register(wait);
        var decision = new HumanDecision(UlidGenerator.shared().next(), "tenant", task, 0, ALICE,
                HumanDecision.Outcome.APPROVE, "private");
        try (var c = owned.getConnection(); var q = c.prepareStatement("insert into human_proposal.task "
                + "(tenant,task_id,instance_id,wait_key,version,status,initiator_kind,initiator_id,assignee_id,separation,risk_tier,policy_id,policy_version) "
                + "values (?,?,?,?,0,'IN_PROGRESS','USER','bob','alice','INITIATOR_MUST_NOT_ACT','HIGH','test-policy','1')")) {
            q.setString(1, "tenant"); q.setString(2, task.value()); q.setString(3, initial.id().value()); q.setString(4, wait.key()); q.executeUpdate();
        }
        var token = new VerifiedToken("issuer", ALICE, Set.of("api"), Set.of("human-task:decide"), "tenant", now,
                now.plusSeconds(120), "id", List.of());
        return new Fixture(wait, decision, CallerAuthority.ofUserToken(token, Clock.systemUTC()));
    }
    private HumanDecisionCoordinator coordinator(InboxWait wait) {
        Clock clock = Clock.systemUTC();
        var admission = new HumanDecisionAdmission(new Pep(ignored -> new Decision("test", DecisionOutcome.ALLOW, "allowed",
                new PolicyRef("test-policy", "1"), clock.instant()), event -> { }, Duration.ofSeconds(3), clock), clock);
        return new HumanDecisionCoordinator(owned, admission, new RegisteredHumanWaitFence(wait), clock);
    }
    private long decisionCount(Fixture f) throws SQLException {
        try (var c = owned.getConnection(); var q = c.prepareStatement("select count(*) from human_proposal.decision where tenant=? and decision_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    private record Staging(JdbcEventInbox inbox, JdbcInboxTaskLease leases, HumanWakeStage stage,
                           InboxTaskClaim claim, InboxListenGroup group) { }
    private Staging staging(Fixture f) {
        coordinator(f.registered()).recordNew(f.authority(), f.decision());
        var inbox = new JdbcEventInbox(owned);
        var leases = new JdbcInboxTaskLease(owned, event -> { }, Clock.systemUTC());
        var matcher = new JdbcOwnedInbox(owned, leases, inbox, new JdbcInboxCheckpointProof(owned), event -> { }, Clock.systemUTC());
        var claim = leases.acquire(f.registered().task(), "test-owner-" + UUID.randomUUID(), Duration.ofSeconds(30)).orElseThrow().claim();
        var group = new InboxListenGroup(f.registered().task(), InboxListenGroup.Strategy.ONE, List.of(FILTER_HASH),
                InboxListenGroup.Stop.NONE, List.of(), null, InboxListenGroup.snapshotFingerprint(SNAPSHOT));
        return new Staging(inbox, leases, new HumanWakeStage(owned, inbox, matcher, Clock.systemUTC()), claim, group);
    }
    private String pendingState(Fixture f) throws SQLException {
        try (var c = owned.getConnection(); var q = c.prepareStatement("select wake_state from human_proposal.pending where tenant=? and decision_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
            try (var r = q.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }
    private long inboxCount(Fixture f) throws SQLException {
        try (var c = owned.getConnection(); var q = c.prepareStatement("select count(*) from inbox.message where tenant=? and source=? and event_id=?")) {
            q.setString(1, "tenant"); q.setString(2, HumanDecisionWake.SOURCE); q.setString(3, f.decision().id().value());
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    @Test void realRegistryAndRegisteredWaitPermitARecordedButBlockedDecision() throws Exception {
        var f = fixture(Duration.ofSeconds(30));
        coordinator(f.registered()).recordNew(f.authority(), f.decision());
        assertEquals(1, decisionCount(f));
        var reader=new HumanWakeRecovery(owned);
        assertTrue(reader.load("other-tenant",f.decision().id()).isEmpty());
        var retained=reader.load("tenant",f.decision().id()).orElseThrow();
        assertEquals(HumanWakeRecovery.State.BLOCKED,retained.state());assertEquals(f.decision(),retained.decision());
        assertEquals(f.registered(),retained.waitProfile());assertFalse(retained.toString().contains("private"));
        try (var c = owned.getConnection(); var q = c.prepareStatement("select wake_state from human_proposal.pending where tenant=? and decision_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
            try (var r = q.executeQuery()) { assertTrue(r.next()); assertEquals("BLOCKED", r.getString(1)); }
        }
    }
    @Test void cancelledRegisteredWaitCannotAcceptDecision() throws Exception {
        var f = fixture(Duration.ofSeconds(30));
        try (var c = owned.getConnection(); var q = c.prepareStatement("update inbox.delivery set state='CANCELLED' "
                + "where tenant=? and instance_id=? and wait_key=?")) {
            q.setString(1, "tenant"); q.setString(2, f.registered().instanceId().value()); q.setString(3, f.registered().key()); q.executeUpdate();
        }
        assertThrows(HumanDecisionCoordinator.Refused.class, () -> coordinator(f.registered()).recordNew(f.authority(), f.decision()));
        assertEquals(0, decisionCount(f));
    }
    @Test void differentLogicalWaitCannotAuthorizeTheTask() throws Exception {
        var f = fixture(Duration.ofSeconds(30));
        var w = f.registered();
        var other = new InboxWait(w.tenant(), w.instanceId(), w.applicationId(), w.engineInstanceId(),
                w.namespace(), w.definition(), w.version(), w.taskPosition(), w.iteration(), w.route(),
                "different.event", w.filterDigest(), w.maxWait());
        assertThrows(HumanDecisionCoordinator.Refused.class, () -> coordinator(other).recordNew(f.authority(), f.decision()));
        assertEquals(0, decisionCount(f));
    }
    @Test void cancelledWorkflowCannotAcceptDecision() throws Exception {
        var f = fixture(Duration.ofSeconds(30));
        var registry = new JdbcInstanceStore(owned);
        var current = registry.find("tenant", f.registered().instanceId()).orElseThrow();
        var time = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        registry.replace(current.successor(time, InstanceStatus.CANCEL_REQUESTED, current.engineInstanceId(),
                current.startAttempts(), null, null), current.version(), "test.cancel", ALICE);
        assertThrows(HumanDecisionCoordinator.Refused.class, () -> coordinator(f.registered()).recordNew(f.authority(), f.decision()));
        assertEquals(0, decisionCount(f));
    }
    @Test void databaseRejectsWaitThatExpiresDuringCommit() throws Exception {
        var f = fixture(Duration.ofSeconds(10));
        try (var c = owned.getConnection(); var s = c.createStatement()) {
            s.execute("create function human_proposal.test_delay() returns trigger language plpgsql as $$ "
                    + "begin perform pg_sleep(11); return null; end $$");
            s.execute("create constraint trigger human_decision_delay after insert on human_proposal.decision "
                    + "deferrable initially deferred for each row execute function human_proposal.test_delay()");
        }
        try {
            assertThrows(HumanDecisionCoordinator.CommitUncertain.class,
                    () -> coordinator(f.registered()).recordNew(f.authority(), f.decision()));
            assertEquals(0, decisionCount(f));
        } finally {
            try (var c = owned.getConnection(); var s = c.createStatement()) {
                s.execute("drop trigger human_decision_delay on human_proposal.decision");
                s.execute("drop function human_proposal.test_delay()");
            }
        }
    }

    @Test void auditedDecisionStagesOneExactReceiptAndReplaysUnderANewOwner() throws Exception {
        var f = fixture(Duration.ofMinutes(2)); var s = staging(f);
        InboxTaskClaim replacement = null;
        try {
            var message = HumanDecisionWake.message(f.decision(), f.registered());
            assertEquals(HumanWakeStage.Result.STAGED, s.stage().stage(f.decision(), f.registered(), s.claim(), s.group(), SNAPSHOT));
            var retained=new HumanWakeRecovery(owned).load("tenant",f.decision().id()).orElseThrow();
            assertEquals(HumanWakeRecovery.State.STAGED,retained.state());assertEquals(f.decision(),retained.decision());
            assertEquals("STAGED", pendingState(f)); assertEquals(1, inboxCount(f));
            assertEquals(Optional.of(message), s.inbox().boundSignal(f.registered()));
            assertEquals(HumanWakeStage.Result.ALREADY_STAGED, s.stage().stage(f.decision(), f.registered(), s.claim(), s.group(), SNAPSHOT));
            assertTrue(s.leases().release(s.claim()));
            replacement = s.leases().acquire(f.registered().task(), "replacement-" + UUID.randomUUID(), Duration.ofSeconds(30)).orElseThrow().claim();
            assertEquals(HumanWakeStage.Result.ALREADY_STAGED, s.stage().stage(f.decision(), f.registered(), replacement, s.group(), SNAPSHOT));
            assertEquals(1, inboxCount(f));
        } finally {
            if (replacement != null) s.leases().release(replacement);
            s.leases().release(s.claim());
        }
    }

    @Test void abruptWorkerExitAfterStagingCanBeRecoveredByANewOwner() throws Exception {
        var f = fixture(Duration.ofMinutes(2)); var s = staging(f);
        assertTrue(s.leases().release(s.claim()));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var arguments = java.nio.file.Files.createTempFile("human-wake-worker-", ".args");
        try {
            java.nio.file.Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n", StandardCharsets.UTF_8);
            Process child = new ProcessBuilder(javaBin, "@" + arguments, HumanWakeCrashWorker.class.getName(), database,
                    f.decision().taskId().value(), f.registered().instanceId().value(), f.decision().id().value(),
                    f.registered().engineInstanceId()).redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(40, java.util.concurrent.TimeUnit.SECONDS), "the wake worker did not stop");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(77, child.exitValue(), output);
            } finally {
                if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS); }
            }
        } finally { java.nio.file.Files.deleteIfExists(arguments); }
        assertEquals("STAGED", pendingState(f)); assertEquals(1, inboxCount(f));
        assertEquals(Optional.of(HumanDecisionWake.message(f.decision(), f.registered())), s.inbox().boundSignal(f.registered()));
        var replacement = new java.util.concurrent.atomic.AtomicReference<InboxTaskClaim>();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            while (replacement.get() == null) {
                s.leases().acquire(f.registered().task(), "recovery-owner", Duration.ofSeconds(30))
                        .ifPresent(lease -> replacement.set(lease.claim()));
                if (replacement.get() == null) Thread.sleep(50);
            }
        });
        try {
            assertEquals(HumanWakeStage.Result.ALREADY_STAGED,
                    s.stage().stage(f.decision(), f.registered(), replacement.get(), s.group(), SNAPSHOT));
            assertEquals(1, inboxCount(f));
        } finally { s.leases().release(replacement.get()); }
    }
    @Test void cancelledStagedWakeIsReportedAsTerminalData() throws Exception {
        var f=fixture(Duration.ofMinutes(2));var s=staging(f);
        try {
            s.stage().stage(f.decision(),f.registered(),s.claim(),s.group(),SNAPSHOT);
            try(var c=owned.getConnection();var q=c.prepareStatement("update inbox.delivery set state='CANCELLED' where tenant=? and instance_id=? and wait_key=?")) {
                q.setString(1,"tenant");q.setString(2,f.registered().instanceId().value());q.setString(3,f.registered().key());assertEquals(1,q.executeUpdate());
            }
            assertEquals(HumanWakeRecovery.State.TERMINAL,new HumanWakeRecovery(owned).load("tenant",f.decision().id()).orElseThrow().state());
        } finally {s.leases().release(s.claim());}
    }
    @Test void recoveryRefusesAMissingWaitProfileRatherThanGuessingIt() throws Exception {
        var f=fixture(Duration.ofMinutes(2));coordinator(f.registered()).recordNew(f.authority(),f.decision());
        try(var c=owned.getConnection();var s=c.createStatement()) {
            s.execute("alter table inbox.wait_profile disable trigger wait_profile_immutable");
            try(var q=c.prepareStatement("delete from inbox.wait_profile where tenant=? and instance_id=? and wait_key=?")) {
                q.setString(1,"tenant");q.setString(2,f.registered().instanceId().value());q.setString(3,f.registered().key());assertEquals(1,q.executeUpdate());
            } finally {s.execute("alter table inbox.wait_profile enable trigger wait_profile_immutable");}
        }
        var error=assertThrows(IllegalStateException.class,()->new HumanWakeRecovery(owned).load("tenant",f.decision().id()));
        assertFalse(error.toString().contains("private"));
    }
    @Test void recoveryRefusesChangedDecisionContentWithoutExposingIt() throws Exception {
        var f=fixture(Duration.ofMinutes(2));coordinator(f.registered()).recordNew(f.authority(),f.decision());
        try(var c=owned.getConnection();var s=c.createStatement()) {
            s.execute("alter table human_proposal.decision disable trigger human_decision_no_change");
            try(var q=c.prepareStatement("update human_proposal.decision set content='private-tampered' where tenant=? and decision_id=?")) {
                q.setString(1,"tenant");q.setString(2,f.decision().id().value());assertEquals(1,q.executeUpdate());
            } finally {s.execute("alter table human_proposal.decision enable trigger human_decision_no_change");}
        }
        var error=assertThrows(IllegalStateException.class,()->new HumanWakeRecovery(owned).load("tenant",f.decision().id()));
        assertFalse(error.toString().contains("private"));
    }

    @Test void staleOwnerRollsBackTheReceiptAndSignal() throws Exception {
        var f = fixture(Duration.ofMinutes(2)); var s = staging(f);
        assertTrue(s.leases().release(s.claim()));
        assertThrows(IllegalStateException.class, () -> s.stage().stage(f.decision(), f.registered(), s.claim(), s.group(), SNAPSHOT));
        assertEquals("BLOCKED", pendingState(f)); assertEquals(0, inboxCount(f));
        assertTrue(s.inbox().boundSignal(f.registered()).isEmpty());
    }

    @Test void forgedDecisionContentCannotStageAReferenceOnlyEvent() throws Exception {
        var f = fixture(Duration.ofMinutes(2)); var s = staging(f);
        try {
            var changed = new HumanDecision(f.decision().id(), "tenant", f.decision().taskId(), 0, ALICE,
                    HumanDecision.Outcome.REJECT, "changed");
            assertThrows(IllegalStateException.class, () -> s.stage().stage(changed, f.registered(), s.claim(), s.group(), SNAPSHOT));
            assertEquals("BLOCKED", pendingState(f)); assertEquals(0, inboxCount(f));
            assertTrue(s.inbox().boundSignal(f.registered()).isEmpty());
        } finally { s.leases().release(s.claim()); }
    }

    @Test void corruptedAuditBindingCannotStageAReferenceOnlyEvent() throws Exception {
        var f = fixture(Duration.ofMinutes(2)); var s = staging(f);
        try {
            // Deliberately bypass only the test database's guards to model retained-data corruption.
            try (var c = owned.getConnection(); var sql = c.createStatement()) {
                sql.execute("alter table human_proposal.pending disable trigger human_pending_no_change");
                sql.execute("alter table human_proposal.pending disable trigger human_pending_update_complete");
                try (var q = c.prepareStatement("update human_proposal.pending set audit_occurred_at=audit_occurred_at + interval '1 second' where tenant=? and decision_id=?")) {
                    q.setString(1, "tenant"); q.setString(2, f.decision().id().value()); assertEquals(1, q.executeUpdate());
                } finally {
                    sql.execute("alter table human_proposal.pending enable trigger human_pending_no_change");
                    sql.execute("alter table human_proposal.pending enable trigger human_pending_update_complete");
                }
            }
            assertThrows(IllegalStateException.class, () -> s.stage().stage(f.decision(), f.registered(), s.claim(), s.group(), SNAPSHOT));
            assertThrows(IllegalStateException.class, () -> new HumanWakeRecovery(owned).load("tenant",f.decision().id()));
            assertEquals("BLOCKED", pendingState(f)); assertEquals(0, inboxCount(f));
            assertTrue(s.inbox().boundSignal(f.registered()).isEmpty());
        } finally { s.leases().release(s.claim()); }
    }

    @Test void pendingCannotBeForgedDirectlyIntoStagedState() throws Exception {
        var f = fixture(Duration.ofMinutes(2));
        coordinator(f.registered()).recordNew(f.authority(), f.decision());
        try (var c = owned.getConnection()) {
            try (var q = c.prepareStatement("insert into human_proposal.pending "
                    + "(tenant,decision_id,audit_event_id,audit_occurred_at,audit_state,wake_state) "
                    + "select tenant,decision_id,audit_event_id,audit_occurred_at,audit_state,'STAGED' "
                    + "from human_proposal.pending where tenant=? and decision_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
                assertEquals("23514", assertThrows(SQLException.class, q::executeUpdate).getSQLState());
            }
            try (var q = c.prepareStatement("update human_proposal.pending set wake_state='STAGED' where tenant=? and decision_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
                assertEquals("23514", assertThrows(SQLException.class, q::executeUpdate).getSQLState());
            }
        }
        assertEquals("BLOCKED", pendingState(f)); assertEquals(0, inboxCount(f));
    }

    @Test void concurrentStageAttemptsRetainOneReceiptAndOneBinding() throws Exception {
        var f = fixture(Duration.ofMinutes(2)); var s = staging(f);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<HumanWakeStage.Result> work = () -> {
                start.await(); return s.stage().stage(f.decision(), f.registered(), s.claim(), s.group(), SNAPSHOT);
            };
            var left = pool.submit(work); var right = pool.submit(work); start.countDown();
            assertEquals(Set.of(HumanWakeStage.Result.STAGED, HumanWakeStage.Result.ALREADY_STAGED),
                    Set.of(left.get(10, java.util.concurrent.TimeUnit.SECONDS), right.get(10, java.util.concurrent.TimeUnit.SECONDS)));
            assertEquals("STAGED", pendingState(f)); assertEquals(1, inboxCount(f));
        } finally { pool.shutdownNow(); s.leases().release(s.claim()); }
    }

    @Test void leaseExpiryDuringCommitRollsBackReceiptSignalAndStage() throws Exception {
        var f = fixture(Duration.ofMinutes(2)); var s = staging(f);
        assertTrue(s.leases().release(s.claim()));
        try (var c = owned.getConnection(); var sql = c.createStatement()) {
            sql.execute("create function human_proposal.test_stage_delay() returns trigger language plpgsql as $$ "
                    + "begin perform pg_sleep(11); return null; end $$");
            sql.execute("create constraint trigger human_stage_delay after insert on human_proposal.wake_stage "
                    + "deferrable initially deferred for each row execute function human_proposal.test_stage_delay()");
        }
        var shortClaim = s.leases().acquire(f.registered().task(), "short-" + UUID.randomUUID(), Duration.ofSeconds(10)).orElseThrow().claim();
        try {
            assertThrows(StoreUnavailableException.class,
                    () -> s.stage().stage(f.decision(), f.registered(), shortClaim, s.group(), SNAPSHOT));
            assertEquals("BLOCKED", pendingState(f)); assertEquals(0, inboxCount(f));
            assertTrue(s.inbox().boundSignal(f.registered()).isEmpty());
        } finally {
            s.leases().release(shortClaim);
            try (var c = owned.getConnection(); var sql = c.createStatement()) {
                sql.execute("drop trigger human_stage_delay on human_proposal.wake_stage");
                sql.execute("drop function human_proposal.test_stage_delay()");
            }
        }
    }
}
