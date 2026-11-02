package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.Set;
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
                + "(tenant,task_id,instance_id,wait_key,version,status,initiator_kind,initiator_id,assignee_id,separation,risk_tier,policy_pin,due_at) "
                + "values (?,?,?,'wait',0,'IN_PROGRESS','USER',?,'alice',?,'HIGH','test-policy',?)")) {
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
        try (var c = owned.getConnection(); var q = c.prepareStatement("select audit_state,wake_state from human_proposal.pending "
                + "where tenant=? and decision_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.decision().id().value());
            try (var r = q.executeQuery()) { assertTrue(r.next()); assertEquals("PENDING", r.getString(1)); assertEquals("BLOCKED", r.getString(2)); }
        }
    }

    @Test void rollbackLeavesNoDecisionOrPendingWakeup() throws Exception {
        var f = fixture("bob", "NONE");
        try (var c = owned.getConnection()) { c.setAutoCommit(false); journal.record(c, f.decision(), f.instance(), "wait"); c.rollback(); }
        assertEquals(0, count("decision", f)); assertEquals(0, count("pending", f));
        assertEquals(HumanDecisionJournal.Result.RECORDED, record(f, f.decision(), "wait"));
    }

    @Test void forbiddenInitiatorCannotWrite() throws Exception {
        var f = fixture("alice", "INITIATOR_MUST_NOT_ACT");
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"));
        assertEquals(0, count("decision", f)); assertEquals(0, count("pending", f));
    }

    @Test void reassignmentVersionChangeAndExpiryAreRefused() throws Exception {
        for (String change : new String[] {"assignee_id='bob'", "version=1", "due_at='2026-10-19T10:00:00Z'"}) {
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
        try (var c = owned.getConnection(); var q = c.prepareStatement("delete from human_proposal.pending where tenant=? and decision_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.decision().id().value()); q.executeUpdate();
        }
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"));
    }

    @Test void changedRetainedWaitCannotReplayTheOldDecision() throws Exception {
        var f = fixture("bob", "NONE");
        record(f, f.decision(), "wait");
        try (var c = owned.getConnection(); var q = c.prepareStatement("update human_proposal.task set wait_key='other' "
                + "where tenant=? and task_id=?")) {
            q.setString(1, "tenant"); q.setString(2, f.task().value()); q.executeUpdate();
        }
        assertThrows(HumanDecisionJournal.Conflict.class, () -> record(f, f.decision(), "wait"));
    }
}
