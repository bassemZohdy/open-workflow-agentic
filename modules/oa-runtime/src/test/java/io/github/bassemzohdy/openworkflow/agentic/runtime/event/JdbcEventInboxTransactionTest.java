package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

/** A receipt can join one real transaction; this does not authorize a producer or dispatch a workflow. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcEventInboxTransactionTest {
    private PGSimpleDataSource admin, owned;
    private String database;
    private boolean created;
    private JdbcEventInbox inbox;
    private static PGSimpleDataSource source(String name) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        if (name != null) ds.setDatabaseName(name);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        return ds;
    }
    @BeforeAll void setup() throws Exception {
        database = "owa_inbox_tx_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(database.matches("owa_inbox_tx_[0-9a-f]{32}")); admin = source(null);
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("create database " + database); created = true; }
        owned = source(database);
        org.flywaydb.core.Flyway.configure().dataSource(owned).locations("classpath:db/migration").load().migrate();
        try (var c = owned.getConnection(); var s = c.createStatement()) { s.execute("create table public.test_receipt_marker (id text primary key)"); }
        inbox = new JdbcEventInbox(owned);
    }
    @AfterAll void cleanup() throws Exception {
        if (!created) return;
        assertTrue(database.matches("owa_inbox_tx_[0-9a-f]{32}"));
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("drop database " + database); }
    }
    private InboxMessage fixture() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var request = new StartRequest("tenant", new Subject(SubjectKind.USER, "alice"),
                new DefinitionRef("agentic/receipt-test", "1"), Map.of(), UUID.randomUUID().toString());
        var row = new JdbcInstanceStore(owned).insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.start").record();
        return new InboxMessage("tenant", "urn:test:transaction", UUID.randomUUID().toString(), "test.event", row.id(), "route", "{\"ok\":true}");
    }
    private long markerCount(String id) throws Exception {
        try (var c = owned.getConnection(); var q = c.prepareStatement("select count(*) from public.test_receipt_marker where id=?")) {
            q.setString(1, id); try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    @Test void rollbackRemovesReceiptAndRelatedWorkTogether() throws Exception {
        var message = fixture(); var until = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
        try (var c = owned.getConnection()) {
            c.setAutoCommit(false);
            assertTrue(inbox.receiveInTransaction(c, message, until).created());
            try (var q = c.prepareStatement("insert into public.test_receipt_marker(id) values (?)")) {
                q.setString(1, message.eventId()); q.executeUpdate();
            }
            assertEquals(0, markerCount(message.eventId()));
            c.rollback();
        }
        assertEquals(0, markerCount(message.eventId()));
        assertTrue(inbox.receive(message, until).created(), "the rolled-back receipt was not committed");
        assertFalse(inbox.receive(message, until).created());
    }
    @Test void commitKeepsReceiptAndRelatedWorkAndPreservesOriginalRetention() throws Exception {
        var message = fixture(); var until = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
        try (var c = owned.getConnection()) {
            c.setAutoCommit(false);
            assertTrue(inbox.receiveInTransaction(c, message, until).created());
            try (var q = c.prepareStatement("insert into public.test_receipt_marker(id) values (?)")) {
                q.setString(1, message.eventId()); q.executeUpdate();
            }
            c.commit();
        }
        assertEquals(1, markerCount(message.eventId()));
        var repeated = inbox.receive(message, until.plusSeconds(100));
        assertFalse(repeated.created()); assertEquals(until, repeated.retainUntil());
        var changed = new InboxMessage(message.tenant(), message.source(), message.eventId(), message.type(),
                message.instanceId(), message.correlationKey(), "{\"ok\":false}");
        assertThrows(IllegalStateException.class, () -> inbox.receive(changed, until));
    }
    @Test void seamRejectsAutocommitAndInvalidJson() throws Exception {
        var message = fixture(); var until = Instant.now().plusSeconds(60);
        try (var c = owned.getConnection()) {
            assertThrows(IllegalArgumentException.class, () -> inbox.receiveInTransaction(c, message, until));
            c.setAutoCommit(false);
            var invalid = new InboxMessage(message.tenant(), message.source(), message.eventId(), message.type(),
                    message.instanceId(), message.correlationKey(), "not-json");
            assertThrows(IllegalArgumentException.class, () -> inbox.receiveInTransaction(c, invalid, until));
            c.rollback();
        }
    }

    @Test void receiptAndExactWaitBindingCommitOrRollBackTogether() throws Exception {
        for (boolean commit : new boolean[] {false, true}) {
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var owner = new Subject(SubjectKind.USER, "alice");
            var request = new StartRequest("tenant", owner, new DefinitionRef("agentic/receipt-test", "1"),
                    Map.of(), UUID.randomUUID().toString());
            var registry = new JdbcInstanceStore(owned);
            var initial = registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.start").record();
            String engine = "engine-" + initial.id().value();
            var running = registry.replace(initial.successor(now, InstanceStatus.RUNNING, engine, 1, null, null),
                    initial.version(), "test.running", owner);
            registry.replace(running.successor(now, InstanceStatus.WAITING, engine, 1, null, null),
                    running.version(), "test.waiting", owner);
            var wait = new InboxWait("tenant", initial.id(), "app", engine, "agentic", "receipt-test", "1", "do/0/wait", 1,
                    "route", "test.event", "0".repeat(64), Duration.ofMinutes(1));
            new JdbcInboxDispatch(owned, (io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink) event -> { }).register(wait);
            var message = new InboxMessage("tenant", "urn:test:transaction", UUID.randomUUID().toString(), "test.event",
                    initial.id(), "route", "{\"ok\":true}");
            var until = Instant.now().plus(Duration.ofDays(1));
            try (var c = owned.getConnection()) {
                c.setAutoCommit(false);
                assertTrue(inbox.receiveInTransaction(c, message, until).created());
                assertEquals(Optional.of(message), inbox.matchCandidate(c, wait, message));
                assertTrue(inbox.boundSignal(wait).isEmpty(), "an independent reader cannot see either uncommitted row");
                if (commit) c.commit(); else c.rollback();
            }
            assertEquals(commit ? Optional.of(message) : Optional.empty(), inbox.boundSignal(wait));
            assertEquals(!commit, inbox.receive(message, until).created());
        }
    }

    @Test void concurrentExactTransactionsKeepOneReceipt() throws Exception {
        var message = fixture(); var until = Instant.now().plusSeconds(600);
        var start = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> receive = () -> {
                start.await();
                try (var c = owned.getConnection()) {
                    c.setAutoCommit(false);
                    try { var result = inbox.receiveInTransaction(c, message, until); c.commit(); return result.created(); }
                    catch (Exception e) { c.rollback(); throw e; }
                }
            };
            var left = workers.submit(receive); var right = workers.submit(receive); start.countDown();
            assertNotEquals(left.get(10, java.util.concurrent.TimeUnit.SECONDS), right.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(inbox.receive(message, until).created());
        } finally { workers.shutdownNow(); }
    }
}
