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
                new DefinitionRef("inbox-test", "1"), Map.of(), "start");
        InstanceRecord row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request,
                Instant.now()), "test.start").record();
        row = move(row, InstanceStatus.RUNNING, null);
        return waiting ? move(row, InstanceStatus.WAITING, null) : row;
    }

    private InstanceRecord move(InstanceRecord row, InstanceStatus status, Instant deadline) {
        return store.replace(row.successor(Instant.now(), status, row.engineInstanceId(), row.startAttempts(),
                null, deadline), row.version(), "test.transition", row.owner());
    }

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
            var result = worker.submit(() -> inbox.match(row.tenant(), row.id(), "wait:1", event.type()));
            c.commit();
            assertTrue(result.get(10, TimeUnit.SECONDS).isEmpty());
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
