package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.JdbcInboxDispatch;
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
        registry.replace(initial.successor(now, InstanceStatus.RUNNING, engine, 1, null, null), initial.version(), "test.running", ALICE);
        var wait = new InboxWait("tenant", initial.id(), "app", engine, "agentic", "human-test", "1",
                "do/0/human", 1, "human", "human.decision", "0".repeat(64), duration);
        new JdbcInboxDispatch(owned, (AuditSink) event -> { }).register(wait);
        Ulid task = UlidGenerator.shared().next();
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
    @Test void realRegistryAndRegisteredWaitPermitARecordedButBlockedDecision() throws Exception {
        var f = fixture(Duration.ofSeconds(30));
        coordinator(f.registered()).recordNew(f.authority(), f.decision());
        assertEquals(1, decisionCount(f));
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
}
