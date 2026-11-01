package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.JdbcEventInbox;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JdbcEventInboxTest {
    @Inject JdbcEventInbox inbox;
    @Inject InstanceStore store;
    @Inject DataSource dataSource;

    private InstanceRecord instance(boolean waiting) {
        String tenant = "inbox-test-" + UUID.randomUUID();
        StartRequest request = new StartRequest(tenant, new Subject(SubjectKind.USER, "alice"),
                new DefinitionRef("agentic/inbox-test", "1"), Map.of(), "start");
        InstanceRecord row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request,
                now()), "test.start").record();
        row = move(row, InstanceStatus.RUNNING, null);
        return waiting ? move(row, InstanceStatus.WAITING, null) : row;
    }

    private InstanceRecord move(InstanceRecord row, InstanceStatus status, Instant deadline) {
        return store.replace(row.successor(now(), status, row.engineInstanceId(), row.startAttempts(),
                null, deadline), row.version(), "test.transition", row.owner());
    }

    private static Instant now() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }

    private InboxMessage message(InstanceRecord row, String id, String key) {
        return new InboxMessage(row.tenant(), "urn:test:orders", id, "order.ready", row.id(), key, "{\"ok\":true}");
    }

    private JdbcEventInbox.Received receive(InboxMessage message) {
        return inbox.receive(message, Instant.now().plusSeconds(300));
    }

    @Test
    void earlyEventSurvivesNewComponentAndMatchesOnlyItsLogicalWait() {
        InstanceRecord row = instance(false);
        InboxMessage event = message(row, "early", "wait:1");
        assertTrue(receive(event).created());
        assertTrue(inbox.match(row.tenant(), row.id(), "wait:1", event.type()).isEmpty());
        row = move(row, InstanceStatus.WAITING, null);
        JdbcEventInbox restarted = new JdbcEventInbox(dataSource);
        assertTrue(restarted.match(row.tenant(), row.id(), "wait:2", event.type()).isEmpty());
        assertEquals(Optional.of(event), restarted.match(row.tenant(), row.id(), "wait:1", event.type()));
        assertEquals(Optional.of(event), restarted.match(row.tenant(), row.id(), "wait:1", event.type()));
        assertThrows(IllegalStateException.class,
                () -> restarted.match(event.tenant(), event.instanceId(), "wait:1", "other.type"));
    }

    // ---- retention sweep ---------------------------------------------------------------------------------------

    private JdbcEventInbox later(Duration ahead) {
        return new JdbcEventInbox(dataSource, Clock.offset(Clock.systemUTC(), ahead));
    }

    private long messages(String tenant) {
        try (Connection c = dataSource.getConnection();
             var s = c.prepareStatement("select count(*) from inbox.message where tenant = ?")) {
            s.setString(1, tenant);
            try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
    }

    // The purge is global and the database is shared with other tests, so these assert on THIS tenant's rows only.

    @Test
    void purgeDeletesOnlyExpiredUnreferencedMessagesAndHonoursTheGrace() {
        InstanceRecord row = instance(true);
        receive(message(row, "old-1", "wait:1"));
        receive(message(row, "old-2", "wait:2"));
        inbox.receive(message(row, "fresh", "wait:3"), Instant.now().plus(Duration.ofHours(5)));
        assertEquals(3, messages(row.tenant()));

        // before any retention ended: nothing of ours is purged
        inbox.purgeExpired(Duration.ZERO, 10_000);
        assertEquals(3, messages(row.tenant()));
        // expired, but inside the grace window: kept
        later(Duration.ofMinutes(10)).purgeExpired(Duration.ofMinutes(30), 10_000);
        assertEquals(3, messages(row.tenant()));
        // expired past the grace: the two old ones go, the fresh one stays
        drain(later(Duration.ofMinutes(10)), Duration.ZERO);
        assertEquals(1, messages(row.tenant()));
        // idempotent: another run changes nothing of ours
        later(Duration.ofMinutes(10)).purgeExpired(Duration.ZERO, 10_000);
        assertEquals(1, messages(row.tenant()));
    }

    /** Purges in bounded calls until a call deletes nothing; returns how many calls deleted something. */
    private int drain(JdbcEventInbox inbox, Duration grace) {
        int calls = 0;
        while (inbox.purgeExpired(grace, 10_000) > 0) {
            if (++calls > 50) fail("the purge never converged");
        }
        return calls;
    }

    @Test
    void aMessageAWaitSignalPointsAtIsKeptEvenAfterItsRetentionEnded() {
        InstanceRecord row = instance(true);
        InboxMessage matched = message(row, "matched", "wait:1");
        receive(matched);
        receive(message(row, "unmatched", "wait:9"));
        assertEquals(Optional.of(matched), inbox.match(row.tenant(), row.id(), "wait:1", matched.type()));

        drain(later(Duration.ofHours(1)), Duration.ZERO);

        assertEquals(1, messages(row.tenant()), "only the unmatched one went; the matched message is the wait's record");
        // and the kept one is still the matched message
        assertEquals(Optional.of(matched), inbox.match(row.tenant(), row.id(), "wait:1", matched.type()));
    }

    @Test
    void purgeIsBoundedByTheLimitPerCall() {
        InstanceRecord row = instance(true);
        for (int i = 0; i < 5; i++) receive(message(row, "bulk-" + i, "wait:" + i));
        var future = later(Duration.ofHours(1));

        int calls = 0;
        while (messages(row.tenant()) > 0) {
            int deleted = future.purgeExpired(Duration.ZERO, 2);
            assertTrue(deleted <= 2, "a call never deletes more than its limit: " + deleted);
            if (++calls > 200) fail("the purge never converged");
        }
        assertTrue(calls >= 3, "5 rows at 2 per call need at least 3 calls, took " + calls);
    }

    @Test
    void anEventIdRedeliveredAfterItsMessageWasPurgedIsANewReceipt() {
        InstanceRecord row = instance(true);
        InboxMessage event = message(row, "redelivered", "wait:1");
        assertTrue(receive(event).created());
        assertFalse(receive(event).created(), "inside retention the duplicate is recognised");

        drain(later(Duration.ofHours(1)), Duration.ZERO);

        assertEquals(0, messages(row.tenant()));
        assertTrue(receive(event).created(), "the duplicate window is the retention period");
    }

    @Test
    void purgeRefusesNegativeGraceAndOutOfRangeLimits() {
        assertThrows(IllegalArgumentException.class, () -> inbox.purgeExpired(Duration.ofSeconds(-1), 10));
        assertThrows(IllegalArgumentException.class, () -> inbox.purgeExpired(Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> inbox.purgeExpired(Duration.ZERO, 10_001));
        assertThrows(NullPointerException.class, () -> inbox.purgeExpired(null, 10));
    }

    @Test
    void duplicateDoesNotExtendRetentionAndConflictingContentOrRoutingIsRefused() {
        InstanceRecord row = instance(true);
        InboxMessage event = message(row, "duplicate", "wait:1");
        var first = receive(event);
        var duplicate = inbox.receive(event, Instant.now().plusSeconds(600));
        assertFalse(duplicate.created()); assertEquals(first.retainUntil(), duplicate.retainUntil());
        InboxMessage otherRoute = message(row, event.eventId(), "wait:2");
        assertThrows(IllegalStateException.class, () -> receive(otherRoute));
        InboxMessage changed = new InboxMessage(event.tenant(), event.source(), event.eventId(), event.type(),
                event.instanceId(), event.correlationKey(), "{\"ok\":false}");
        assertThrows(IllegalStateException.class, () -> receive(changed));
        assertEquals(Optional.of(event), inbox.match(row.tenant(), row.id(), "wait:1", event.type()));
    }

    @Test
    void outOfOrderEventsRemainBoundToTheirOwnWaitAndFirstReceiptWins() {
        InstanceRecord row = instance(true);
        InboxMessage second = message(row, "second", "wait:2");
        InboxMessage first = message(row, "first", "wait:1");
        InboxMessage another = message(row, "another", "wait:1");
        receive(second); receive(first); receive(another);
        assertEquals(Optional.of(first), inbox.match(row.tenant(), row.id(), "wait:1", first.type()));
        assertEquals(Optional.of(second), inbox.match(row.tenant(), row.id(), "wait:2", second.type()));
        assertEquals(Optional.of(first), inbox.match(row.tenant(), row.id(), "wait:1", first.type()));
    }

    @Test
    void tenantAndSourceArePartOfDeduplicationAndTargetScope() {
        InstanceRecord a = instance(true), b = instance(true);
        InboxMessage ea = message(a, "same-id", "wait:1"), eb = message(b, "same-id", "wait:1");
        assertTrue(receive(ea).created()); assertTrue(receive(eb).created());
        InboxMessage wrong = new InboxMessage(a.tenant(), eb.source(), "wrong-target", eb.type(), b.id(),
                eb.correlationKey(), eb.dataJson());
        assertThrows(NoSuchElementException.class, () -> receive(wrong));
        assertThrows(NoSuchElementException.class, () -> inbox.match(a.tenant(), b.id(), "wait:1", ea.type()));
        InboxMessage anotherSource = new InboxMessage(a.tenant(), "urn:test:other", ea.eventId(), ea.type(),
                a.id(), "wait:2", ea.dataJson());
        assertTrue(receive(anotherSource).created());
    }

    @Test
    void terminalCancellationAndExpiredWaitsNeverCreateNewSignals() {
        for (InstanceStatus status : List.of(InstanceStatus.CANCEL_REQUESTED, InstanceStatus.COMPLETED, InstanceStatus.FAILED)) {
            InstanceRecord row = instance(true);
            InboxMessage event = message(row, "late", "wait:1"); receive(event);
            row = move(row, status, null);
            assertTrue(inbox.match(row.tenant(), row.id(), "wait:1", event.type()).isEmpty());
        }
        InstanceRecord row = instance(true);
        InboxMessage event = message(row, "deadline", "wait:1"); receive(event);
        row = move(row, InstanceStatus.WAITING, Instant.now().minusSeconds(1));
        assertTrue(inbox.match(row.tenant(), row.id(), "wait:1", event.type()).isEmpty());
    }

    @Test
    void expiredEarlyEventCannotWakeAWait() {
        InstanceRecord row = instance(true);
        InboxMessage event = message(row, "expired", "wait:1");
        JdbcEventInbox yesterday = new JdbcEventInbox(dataSource, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
        yesterday.receive(event, Instant.now().minusSeconds(3600));
        assertTrue(inbox.match(row.tenant(), row.id(), "wait:1", event.type()).isEmpty());
    }

    @Test
    void concurrentReceiptsAndMatchesProduceOneMessageAndOneSignal() throws Exception {
        InstanceRecord row = instance(true);
        InboxMessage event = message(row, "concurrent", "wait:1");
        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> receives = new ArrayList<>();
            for (int i = 0; i < 4; i++) receives.add(() -> receive(event).created());
            int created = 0;
            for (Future<Boolean> f : workers.invokeAll(receives)) if (f.get()) created++;
            assertEquals(1, created);
            List<Callable<Optional<InboxMessage>>> matches = new ArrayList<>();
            for (int i = 0; i < 4; i++) matches.add(() -> inbox.match(row.tenant(), row.id(), "wait:1", event.type()));
            for (var f : workers.invokeAll(matches)) assertEquals(Optional.of(event), f.get());
        }
    }

    @Test
    void committedCancellationWinsAgainstAMatcherWaitingOnTheInstanceLock() throws Exception {
        InstanceRecord row = instance(true);
        InboxMessage event = message(row, "cancel-race", "wait:1"); receive(event);
        try (Connection c = dataSource.getConnection(); ExecutorService worker = Executors.newSingleThreadExecutor()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("update task.instance set status='CANCEL_REQUESTED', version=version+1 "
                    + "where tenant=? and id=?")) {
                s.setString(1, row.tenant()); s.setString(2, row.id().value()); assertEquals(1, s.executeUpdate());
            }
            String marker = "inbox-race-" + UUID.randomUUID();
            DataSource tagged = (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(dataSource, args);
                            if (value instanceof Connection connection) connection.setClientInfo("ApplicationName", marker);
                            return value;
                        } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
            var result = worker.submit(() -> new JdbcEventInbox(tagged).match(row.tenant(), row.id(), "wait:1", event.type()));
            try {
                // Observe actual PostgreSQL lock contention, not just executor scheduling.
                boolean blocked = false;
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try (var observer = dataSource.getConnection(); var s = observer.prepareStatement(
                        "select exists (select 1 from pg_stat_activity where application_name=? and wait_event_type='Lock')")) {
                    observer.setAutoCommit(true); s.setString(1, marker);
                    while (System.nanoTime() < until && !result.isDone()) {
                        try (var r = s.executeQuery()) { r.next(); blocked = r.getBoolean(1); }
                        if (blocked) break;
                        Thread.sleep(20);
                    }
                }
                assertTrue(blocked, "matcher must wait on the cancellation transaction's instance lock");
                c.commit();
                assertTrue(result.get(10, TimeUnit.SECONDS).isEmpty());
            } finally { c.rollback(); }
        }
    }

    @Test
    void invalidJsonOrRetentionCannotCreateAReceipt() {
        InstanceRecord row = instance(true);
        InboxMessage event = message(row, "bad", "wait:1");
        assertThrows(IllegalArgumentException.class, () -> inbox.receive(event, Instant.now().minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> inbox.receive(event, Instant.now().plus(Duration.ofDays(31))));
        for (String invalid : List.of("not-json", "{} {}", "")) {
            InboxMessage bad = new InboxMessage(event.tenant(), event.source(), event.eventId(), event.type(),
                    event.instanceId(), event.correlationKey(), invalid);
            assertThrows(IllegalArgumentException.class, () -> receive(bad));
        }
        assertTrue(receive(event).created());
    }
}
