package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.HumanDecisionAdmission;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HumanDecisionJournalTest {
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private final HumanDecisionJournal journal = new HumanDecisionJournal(Clock.fixed(NOW, ZoneOffset.UTC));
    private PGSimpleDataSource admin;
    private PGSimpleDataSource owned;
    private String database;
    private boolean created;

    private static PGSimpleDataSource source(String database) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        if (database != null) ds.setDatabaseName(database);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        ds.setConnectTimeout(5); ds.setSocketTimeout(10);
        return ds;
    }
    @BeforeAll void createDatabase() throws Exception {
        admin = source(null); database = "owa_human_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(database.matches("owa_human_[0-9a-f]{32}"));
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("create database " + database); created = true; }
        owned = source(database);
        org.flywaydb.core.Flyway.configure().dataSource(owned).locations("classpath:db/migration").load().migrate();
        try (var in = getClass().getResourceAsStream("/human/proposed-human-decision.sql")) {
            assertNotNull(in);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (var c = owned.getConnection(); var s = c.createStatement()) { s.execute(sql); }
        }
    }
    @AfterAll void dropDatabase() throws Exception {
        if (!created) return;
        assertTrue(database.matches("owa_human_[0-9a-f]{32}"));
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("drop database " + database); }
    }
    private record Fixture(Ulid task, Ulid instance, HumanDecision decision) { }
    private Fixture fixture(String initiator, String separation) throws SQLException {
        Ulid task = UlidGenerator.shared().next(); Ulid instance = UlidGenerator.shared().next();
        var decision = new HumanDecision(UlidGenerator.shared().next(), "tenant", task, 0, ALICE,
                HumanDecision.Outcome.APPROVE, "private");
        try (var c = owned.getConnection(); var q = c.prepareStatement("insert into human_proposal.task "
                + "(tenant,task_id,instance_id,wait_key,version,status,initiator_kind,initiator_id,assignee_id,separation,risk_tier,policy_id,policy_version,due_at) "
                + "values (?,?,?,'wait',0,'IN_PROGRESS','USER',?,'alice',?,'HIGH','test-policy','1',?)")) {
            q.setString(1, "tenant"); q.setString(2, task.value()); q.setString(3, instance.value());
            q.setString(4, initiator); q.setString(5, separation); q.setTimestamp(6, java.sql.Timestamp.from(NOW.plusSeconds(60)));
            q.executeUpdate();
        }
        return new Fixture(task, instance, decision);
    }
    private HumanDecisionJournal.Result record(Fixture f, HumanDecision decision, String wait) throws Exception {
        try (var c = owned.getConnection()) {
            c.setAutoCommit(false);
            try { var result = journal.record(c, decision, f.instance(), wait); c.commit(); return result; }
            catch (Exception e) { c.rollback(); throw e; }
        }
    }
    private long count(String table, Fixture f) throws SQLException {
        if (!Set.of("decision", "pending").contains(table)) throw new IllegalArgumentException();
        try (var c = owned.getConnection(); var q = c.prepareStatement("select count(*) from human_proposal." + table
                + " where tenant=? and decision_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    @Test void exactReplayIsStableAndChangedContentConflicts() throws Exception {
        var f = fixture("bob", "INITIATOR_MUST_NOT_ACT");
        assertEquals(HumanDecisionJournal.Result.RECORDED, record(f, f.decision(), "wait"));
        assertEquals(HumanDecisionJournal.Result.REPLAYED, record(f, f.decision(), "wait"));
        var changed = new HumanDecision(f.decision().id(), "tenant", f.task(), 0, ALICE,
                HumanDecision.Outcome.REJECT, "private");
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, changed, "wait"));
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "other-wait"));
        assertEquals(1, count("decision", f)); assertEquals(1, count("pending", f));
        try (var c = owned.getConnection(); var q = c.prepareStatement("select event_type,attributes::text from audit.event where event_id=?")) {
            q.setString(1, f.decision().id().value());
            try (var r = q.executeQuery()) {
                assertTrue(r.next()); assertEquals("human.decision.recorded", r.getString(1));
                assertFalse(r.getString(2).contains("private"), "private decision content is not written to audit attributes");
            }
        }
        try (var c = owned.getConnection(); var q = c.prepareStatement("select audit_state,wake_state from human_proposal.pending "
                + "where tenant=? and decision_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
            try (var r = q.executeQuery()) { assertTrue(r.next()); assertEquals("RECORDED", r.getString(1)); assertEquals("BLOCKED", r.getString(2)); }
        }
    }

    @Test void rollbackLeavesNoDecisionOrPendingWakeup() throws Exception {
        var f = fixture("bob", "NONE");
        try (var c = owned.getConnection()) { c.setAutoCommit(false); journal.record(c, f.decision(), f.instance(), "wait"); c.rollback(); }
        assertEquals(0, count("decision", f)); assertEquals(0, count("pending", f));
        assertEquals(0, auditCount(f));
        assertEquals(HumanDecisionJournal.Result.RECORDED, record(f, f.decision(), "wait"));
        assertEquals(1, auditCount(f));
    }

    @Test void aSimulatedLostCommitReplyIsRecoveredByTheSameDecision() throws Exception {
        var f = fixture("bob", "NONE");
        try (var c = owned.getConnection()) {
            c.setAutoCommit(false);
            assertEquals(HumanDecisionJournal.Result.RECORDED, journal.record(c, f.decision(), f.instance(), "wait"));
            assertThrows(SQLException.class, () -> {
                c.commit();
                throw new SQLException("simulated reply loss after commit");
            });
        }
        assertEquals(HumanDecisionJournal.Result.REPLAYED, record(f, f.decision(), "wait"));
        assertEquals(1, count("decision", f)); assertEquals(1, count("pending", f)); assertEquals(1, auditCount(f));
    }

    @Test void failedAuditAppendRollsBackDecisionAndTaskTransition() throws Exception {
        var f = fixture("bob", "NONE");
        try (var c = owned.getConnection(); var s = c.createStatement()) {
            s.execute("create function human_proposal.refuse_audit() returns trigger language plpgsql as $$ "
                    + "begin raise exception 'injected audit failure'; end $$");
            s.execute("create trigger human_test_refuse before insert on audit.event "
                    + "for each row execute function human_proposal.refuse_audit()");
        }
        try {
            assertThrows(SQLException.class, () -> record(f, f.decision(), "wait"));
            assertEquals(0, count("decision", f)); assertEquals(0, count("pending", f)); assertEquals(0, auditCount(f));
        } finally {
            try (var c = owned.getConnection(); var s = c.createStatement()) {
                s.execute("drop trigger human_test_refuse on audit.event");
                s.execute("drop function human_proposal.refuse_audit()");
            }
        }
        assertEquals(HumanDecisionJournal.Result.RECORDED, record(f, f.decision(), "wait"));
        assertEquals(1, auditCount(f));
    }

    @Test void forbiddenInitiatorCannotWrite() throws Exception {
        var f = fixture("alice", "INITIATOR_MUST_NOT_ACT");
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"));
        assertEquals(0, count("decision", f)); assertEquals(0, count("pending", f));
    }

    @Test void reassignmentVersionChangeAndExpiryAreRefused() throws Exception {
        for (String change : new String[] {"assignee_id='bob',version=version+1", "version=1",
                "due_at='2026-10-19T10:00:00Z',version=version+1"}) {
            var f = fixture("bob", "NONE");
            try (var c = owned.getConnection(); var q = c.prepareStatement("update human_proposal.task set " + change
                    + " where tenant=? and task_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.task().value()); q.executeUpdate();
            }
            assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"), change);
            assertEquals(0, count("decision", f)); assertEquals(0, count("pending", f));
        }
    }

    @Test void concurrentExactRetriesRecordOneDecision() throws Exception {
        var f = fixture("bob", "NONE");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<HumanDecisionJournal.Result> work = () -> { start.await(); return record(f, f.decision(), "wait"); };
            var first = pool.submit(work); var second = pool.submit(work); start.countDown();
            assertEquals(Set.of(HumanDecisionJournal.Result.RECORDED, HumanDecisionJournal.Result.REPLAYED),
                    Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            assertEquals(1, count("decision", f)); assertEquals(1, count("pending", f));
            assertEquals(1, auditCount(f));
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentOppositeDecisionsCannotBothCommit() throws Exception {
        var f = fixture("bob", "NONE");
        var reject = new HumanDecision(UlidGenerator.shared().next(), "tenant", f.task(), 0, ALICE,
                HumanDecision.Outcome.REJECT, "other");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> approve = () -> { start.await(); try { record(f, f.decision(), "wait"); return "recorded"; }
                catch (HumanDecisionJournal.Conflict e) { return "conflict"; } };
            Callable<String> rejectWork = () -> { start.await(); try { record(f, reject, "wait"); return "recorded"; }
                catch (HumanDecisionJournal.Conflict e) { return "conflict"; } };
            var first = pool.submit(approve); var second = pool.submit(rejectWork); start.countDown();
            assertEquals(Set.of("recorded", "conflict"), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            try (var c = owned.getConnection(); var q = c.prepareStatement("select count(*) from human_proposal.decision "
                    + "where tenant=? and task_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.task().value());
                try (var r = q.executeQuery()) { r.next(); assertEquals(1, r.getLong(1)); }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void missingPendingStateIsNotMistakenForASuccessfulReplay() throws Exception {
        var f = fixture("bob", "NONE");
        record(f, f.decision(), "wait");
        try (var c = owned.getConnection(); var s = c.createStatement()) {
            s.execute("alter table human_proposal.pending disable trigger human_pending_no_change");
            try (var q = c.prepareStatement("delete from human_proposal.pending where tenant=? and decision_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.decision().id().value()); q.executeUpdate();
            } finally { s.execute("alter table human_proposal.pending enable trigger human_pending_no_change"); }
        }
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"));
    }

    @Test void changedRetainedWaitCannotReplayTheOldDecision() throws Exception {
        var f = fixture("bob", "NONE");
        record(f, f.decision(), "wait");
        try (var c = owned.getConnection(); var s = c.createStatement()) {
            s.execute("alter table human_proposal.task disable trigger human_task_guard");
            s.execute("alter table human_proposal.task disable trigger human_task_complete");
            try (var q = c.prepareStatement("update human_proposal.task set wait_key='other' where tenant=? and task_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.task().value()); q.executeUpdate();
            } finally {
                s.execute("alter table human_proposal.task enable trigger human_task_complete");
                s.execute("alter table human_proposal.task enable trigger human_task_guard");
            }
        }
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"));
    }

    @Test void changedRetainedAuditTimeCannotReplayTheOldDecision() throws Exception {
        var f = fixture("bob", "NONE");
        record(f, f.decision(), "wait");
        try (var c = owned.getConnection(); var s = c.createStatement()) {
            s.execute("alter table human_proposal.pending disable trigger human_pending_no_change");
            try (var q = c.prepareStatement("update human_proposal.pending "
                    + "set audit_occurred_at=audit_occurred_at + interval '1 second' where tenant=? and decision_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.decision().id().value()); q.executeUpdate();
            } finally { s.execute("alter table human_proposal.pending enable trigger human_pending_no_change"); }
        }
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"));
    }

    private long auditCount(Fixture f) throws SQLException {
        try (var c = owned.getConnection(); var q = c.prepareStatement("select count(*) from audit.event where event_id=?")) {
            q.setString(1, f.decision().id().value());
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    private CallerAuthority caller(Clock clock, Instant expiry) {
        return CallerAuthority.ofUserToken(new VerifiedToken("issuer", ALICE, Set.of("api"),
                Set.of("human-task:decide"), "tenant", NOW, expiry, "id", List.of()), clock);
    }

    private HumanDecisionAdmission admission(Clock clock, io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint policy) {
        return new HumanDecisionAdmission(new Pep(policy, event -> { }, Duration.ofSeconds(3), clock), clock);
    }

    private Decision allowed() {
        return new Decision("test", DecisionOutcome.ALLOW, "allowed", new PolicyRef("test-policy", "1"), NOW);
    }

    @Test void coordinatorCommitsOnlyAfterBothFenceChecks() throws Exception {
        var f = fixture("bob", "INITIATOR_MUST_NOT_ACT");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var coordinator = new HumanDecisionCoordinator(owned, admission(clock, ignored -> allowed()), (c, tenant, instance, wait) -> {
            assertFalse(c.getAutoCommit()); assertEquals("tenant", tenant); assertEquals(f.instance(), instance);
            assertEquals("wait", wait); checks.incrementAndGet();
        }, clock);
        coordinator.recordNew(caller(clock, NOW.plusSeconds(60)), f.decision());
        assertEquals(2, checks.get()); assertEquals(1, count("decision", f)); assertEquals(1, auditCount(f));
        assertEquals(HumanDecisionJournal.Result.REPLAYED, record(f, f.decision(), "wait"));
    }

    @Test void coordinatorRefusesTaskChangesDuringPolicyEvaluation() throws Exception {
        var f = fixture("bob", "NONE");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var coordinator = new HumanDecisionCoordinator(owned, admission(clock, ignored -> {
            try (var c = owned.getConnection(); var q = c.prepareStatement("update human_proposal.task "
                    + "set assignee_id='bob',version=version+1 where tenant=? and task_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.task().value()); q.executeUpdate();
            } catch (SQLException e) { throw new IllegalStateException(e); }
            return allowed();
        }), (c, tenant, instance, wait) -> fail("stale facts must never reach the fence"), clock);
        assertThrows(HumanDecisionCoordinator.Refused.class,
                () -> coordinator.recordNew(caller(clock, NOW.plusSeconds(60)), f.decision()));
        assertEquals(0, count("decision", f)); assertEquals(0, auditCount(f));
    }

    @Test void expiryAfterAuditAndFenceFailureRollBackEverything() throws Exception {
        var f = fixture("bob", "NONE");
        var current = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return current.get(); }
        };
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var coordinator = new HumanDecisionCoordinator(owned, admission(clock, ignored -> allowed()), (c, tenant, instance, wait) -> {
            if (checks.incrementAndGet() == 2) current.set(NOW.plusSeconds(2));
        }, clock);
        assertThrows(AuthorityLostException.class,
                () -> coordinator.recordNew(caller(clock, NOW.plusSeconds(1)), f.decision()));
        assertEquals(0, count("decision", f)); assertEquals(0, auditCount(f)); assertEquals(0, count("pending", f));

        current.set(NOW); checks.set(0);
        var failingFence = new HumanDecisionCoordinator(owned, admission(clock, ignored -> allowed()), (c, tenant, instance, wait) -> {
            if (checks.incrementAndGet() == 2) throw new SQLException("simulated lease loss");
        }, clock);
        assertThrows(HumanDecisionCoordinator.Unavailable.class,
                () -> failingFence.recordNew(caller(clock, NOW.plusSeconds(60)), f.decision()));
        assertEquals(0, count("decision", f)); assertEquals(0, auditCount(f)); assertEquals(0, count("pending", f));
    }

    @Test void coordinatorRefusesPolicyVersionMismatch() throws Exception {
        var f = fixture("bob", "NONE");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var coordinator = new HumanDecisionCoordinator(owned, admission(clock, ignored ->
                new Decision("test", DecisionOutcome.ALLOW, "allowed", new PolicyRef("other", "1"), NOW)),
                (c, tenant, instance, wait) -> fail("policy mismatch must not reach the fence"), clock);
        assertThrows(HumanDecisionCoordinator.Refused.class,
                () -> coordinator.recordNew(caller(clock, NOW.plusSeconds(60)), f.decision()));
        assertEquals(0, count("decision", f));
    }

    @Test void coordinatorRefusesForbiddenInitiatorAndPolicyAuditFailure() throws Exception {
        var forbidden = fixture("alice", "INITIATOR_MUST_NOT_ACT");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var coordinator = new HumanDecisionCoordinator(owned, admission(clock, ignored -> allowed()),
                (c, tenant, instance, wait) -> fail("forbidden actor must not reach the fence"), clock);
        assertThrows(HumanDecisionCoordinator.Refused.class,
                () -> coordinator.recordNew(caller(clock, NOW.plusSeconds(60)), forbidden.decision()));
        assertEquals(0, count("decision", forbidden)); assertEquals(0, auditCount(forbidden));

        var f = fixture("bob", "NONE");
        var failedAudit = new HumanDecisionAdmission(new Pep(ignored -> allowed(), event -> {
            throw new IllegalStateException("audit unavailable");
        }, Duration.ofSeconds(3), clock), clock);
        var denied = new HumanDecisionCoordinator(owned, failedAudit,
                (c, tenant, instance, wait) -> fail("unrecorded policy decision must not reach the fence"), clock);
        assertThrows(HumanDecisionCoordinator.Refused.class,
                () -> denied.recordNew(caller(clock, NOW.plusSeconds(60)), f.decision()));
        assertEquals(0, count("decision", f)); assertEquals(0, auditCount(f));
    }

    @Test void coordinatorCommitsOnlyOneOfTwoConcurrentDecisions() throws Exception {
        var f = fixture("bob", "NONE");
        var reject = new HumanDecision(UlidGenerator.shared().next(), "tenant", f.task(), 0, ALICE,
                HumanDecision.Outcome.REJECT, "other");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var coordinator = new HumanDecisionCoordinator(owned, admission(clock, ignored -> allowed()),
                (c, tenant, instance, wait) -> { }, clock);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> approve = () -> { start.await(); try {
                coordinator.recordNew(caller(clock, NOW.plusSeconds(60)), f.decision()); return "committed";
            } catch (HumanDecisionCoordinator.Refused | SecurityException e) { return "refused"; } };
            Callable<String> rejectWork = () -> { start.await(); try {
                coordinator.recordNew(caller(clock, NOW.plusSeconds(60)), reject); return "committed";
            } catch (HumanDecisionCoordinator.Refused | SecurityException e) { return "refused"; } };
            var first = pool.submit(approve); var second = pool.submit(rejectWork); start.countDown();
            assertEquals(Set.of("committed", "refused"), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            try (var c = owned.getConnection(); var q = c.prepareStatement("select count(*) from human_proposal.decision "
                    + "where tenant=? and task_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.task().value());
                try (var r = q.executeQuery()) { r.next(); assertEquals(1, r.getLong(1)); }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void coordinatorReportsCommitReplyLossAsUncertain() throws Exception {
        var f = fixture("bob", "NONE");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var connections = new java.util.concurrent.atomic.AtomicInteger();
        var uncertain = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class}, (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(owned, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    if (!method.getName().equals("getConnection") || connections.incrementAndGet() != 2) return result;
                    var delegate = (Connection) result;
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                            (ignored, operation, parameters) -> {
                                Object value;
                                try { value = operation.invoke(delegate, parameters); }
                                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                                if (operation.getName().equals("commit")) throw new SQLException("simulated lost reply");
                                return value;
                            });
                });
        var coordinator = new HumanDecisionCoordinator(uncertain, admission(clock, ignored -> allowed()),
                (c, tenant, instance, wait) -> { }, clock);
        assertThrows(HumanDecisionCoordinator.CommitUncertain.class,
                () -> coordinator.recordNew(caller(clock, NOW.plusSeconds(60)), f.decision()));
        assertEquals(1, count("decision", f)); assertEquals(1, auditCount(f));
        assertEquals(HumanDecisionJournal.Result.REPLAYED, record(f, f.decision(), "wait"));
    }

    @Test void databaseRejectsUnversionedChangesAndCompletedTaskWithoutDecision() throws Exception {
        var f = fixture("bob", "NONE");
        try (var c = owned.getConnection()) {
            try (var q = c.prepareStatement("update human_proposal.task set assignee_id='bob' where tenant=? and task_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.task().value());
                assertThrows(SQLException.class, q::executeUpdate);
            }
            try (var q = c.prepareStatement("update human_proposal.task set status='COMPLETED',version=version+1 "
                    + "where tenant=? and task_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.task().value());
                assertThrows(SQLException.class, q::executeUpdate);
            }
        }
        assertEquals(HumanDecisionJournal.Result.RECORDED, record(f, f.decision(), "wait"));
    }

    @Test void committedDecisionAndPendingRowsCannotBeRewritten() throws Exception {
        var f = fixture("bob", "NONE");
        record(f, f.decision(), "wait");
        try (var c = owned.getConnection()) {
            try (var q = c.prepareStatement("update human_proposal.decision set content='changed' where tenant=? and decision_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
                assertThrows(SQLException.class, q::executeUpdate);
            }
            try (var q = c.prepareStatement("delete from human_proposal.pending where tenant=? and decision_id=?")) {
                q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
                assertThrows(SQLException.class, q::executeUpdate);
            }
        }
        assertEquals(HumanDecisionJournal.Result.REPLAYED, record(f, f.decision(), "wait"));
    }
}
