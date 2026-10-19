package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.count;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.current;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.move;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newInstance;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newPoisonTenant;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.relay;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxAdmin;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxRelay;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxRelayService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.RelaySettings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewProjector;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewQuery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewFilter;
import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The relay (WP-105b): at-least-once redelivery, several relays at once, poison rows, and the switch that turns the
 * relay off in a process.
 */
@QuarkusTest
@TestProfile(OutboxProfile.class)
class OutboxRelayTest {

    @Inject
    JdbcInstanceStore instances;

    @Inject
    TaskViewProjector projector;

    @Inject
    TaskViewQuery views;

    @Inject
    OutboxAdmin admin;

    @Inject
    OutboxRelayService service;

    @Inject
    DataSource dataSource;

    @Test
    void aRowDeliveredTwiceChangesNothingTheSecondTime() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        move(instances, current(instances, tenant, id), InstanceStatus.RUNNING);

        OutboxFixture.RecordingConsumer recording = new OutboxFixture.RecordingConsumer();
        OutboxRelay relay = relay(dataSource, projector, recording);
        relay.drainAll(10_000);
        var before = views.list(TaskViewFilter.forTenant(tenant), PageRequest.firstPage()).items();

        // The relay's commit was lost after its writes reached the view: the row is redelivered.
        assertEquals(2, update(dataSource, "update task.outbox set published_at = null where aggregate_id = ?",
                id.value()));
        relay.drainAll(10_000);

        assertEquals(before, views.list(TaskViewFilter.forTenant(tenant), PageRequest.firstPage()).items());
        List<Long> versions = recording.forPartition(id.value()).stream().map(OutboxEvent::aggregateVersion).toList();
        assertEquals(List.of(0L, 1L, 0L, 1L), versions, "each event was handed over twice, in order");
    }

    @Test
    void fourRelaysDeliverEveryRowOnceAndInOrderWithinAnInstance() throws Exception {
        String tenant = newTenant();
        List<Ulid> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Ulid id = newInstance(instances, tenant);
            ids.add(id);
            var row = current(instances, tenant, id);
            row = move(instances, row, InstanceStatus.RUNNING);
            row = move(instances, row, InstanceStatus.WAITING);
            row = move(instances, row, InstanceStatus.RUNNING);
            move(instances, row, InstanceStatus.COMPLETED);
        }
        assertEquals(300L, count(dataSource, """
                select count(*) from task.outbox o join task.instance i on i.id = o.partition_key
                 where i.tenant = ?
                """, tenant), "five history rows for each of sixty instances");

        OutboxFixture.RecordingConsumer recording = new OutboxFixture.RecordingConsumer();
        int relays = 4;
        ExecutorService pool = Executors.newFixedThreadPool(relays);
        CyclicBarrier start = new CyclicBarrier(relays);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int r = 0; r < relays; r++) {
                OutboxRelay relay = relay(dataSource, projector, recording);
                done.add(pool.submit(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                    while (System.nanoTime() < deadline) {
                        relay.drainOnce();
                        if (unpublished(tenant) == 0) {
                            return null;
                        }
                        Thread.sleep(2);
                    }
                    throw new AssertionError("the relays did not finish in time");
                }));
            }
            for (Future<?> f : done) {
                f.get(150, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0L, unpublished(tenant));
        Set<Long> seen = new HashSet<>();
        long deliveries = 0;
        for (OutboxEvent event : recording.delivered) {
            if (event.payload().path("tenant").asText().equals(tenant)) {
                deliveries++;
                assertTrue(seen.add(event.id()), "row " + event.id() + " was delivered twice");
            }
        }
        assertEquals(300L, deliveries, "every row was delivered exactly once");
        for (Ulid id : ids) {
            List<Long> versions = recording.forPartition(id.value()).stream()
                    .map(OutboxEvent::aggregateVersion).toList();
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L), versions, "instance " + id + " was delivered in order");
        }
        assertEquals(60L, views.list(TaskViewFilter.forTenant(tenant), PageRequest.of(null, 200)).items().size());
    }

    @Test
    void aRowThatKeepsFailingIsMarkedFailedAndBlocksOnlyItsOwnInstance() {
        OutboxFixture.FlakyConsumer flaky = new OutboxFixture.FlakyConsumer();
        OutboxFixture.RecordingConsumer recording = new OutboxFixture.RecordingConsumer();
        // The projector is wired as in production, so every published row is also in the view.
        OutboxRelay relay = new OutboxRelay(dataSource, List.of(flaky, projector, recording),
                new RelaySettings(20, 3, Duration.ZERO, Duration.ZERO, Duration.ofMillis(100)));

        String poison = newPoisonTenant();
        String healthy = newTenant();
        Ulid bad = newInstance(instances, poison);
        move(instances, current(instances, poison, bad), InstanceStatus.RUNNING);
        Ulid good = newInstance(instances, healthy);
        move(instances, current(instances, healthy, good), InstanceStatus.RUNNING);

        relay.drainAll(10_000);

        assertEquals(2, recording.forPartition(good.value()).size(), "the healthy instance is delivered");
        assertTrue(recording.forPartition(bad.value()).isEmpty(), "nothing of the poisoned instance got past it");
        long failedId = count(dataSource, """
                select id from task.outbox where aggregate_id = ? and aggregate_version = 0
                """, bad.value());
        assertEquals(3L, count(dataSource, "select attempts from task.outbox where id = ?", failedId));
        assertEquals(1L, count(dataSource,
                "select count(*) from task.outbox where id = ? and failed_at is not null", failedId));
        assertEquals("java.lang.IllegalStateException", lastError(failedId));
        assertEquals(1L, count(dataSource,
                "select count(*) from task.outbox where aggregate_id = ? and failed_at is not null", bad.value()),
                "only the first row of that instance is failed; its later rows wait unattempted");
        assertEquals(0L, count(dataSource,
                "select attempts from task.outbox where aggregate_id = ? and aggregate_version = 1", bad.value()));
        assertTrue(admin.failures(1000).stream().anyMatch(f -> f.id() == failedId), "the operator can see it");
        assertTrue(admin.lag().failed() >= 1);

        // The operator fixes the cause and re-queues the row. It is delivered now, and the rest of the instance follows.
        flaky.failing.set(false);
        assertTrue(admin.requeue(failedId));
        assertFalse(admin.requeue(failedId), "a row that is not failed is not re-queued");
        relay.drainAll(10_000);

        List<Long> versions = recording.forPartition(bad.value()).stream()
                .map(OutboxEvent::aggregateVersion).toList();
        assertEquals(List.of(0L, 1L), versions, "the poisoned instance is delivered in order after the requeue");
        assertNull(lastErrorOrNull(failedId), "a delivered row has no error");
    }

    @Test
    void theRelayIsOffInAProcessThatIsConfiguredSo() {
        assertFalse(service.enabled());
        assertTrue(service.relay().isEmpty());
    }

    private long unpublished(String tenant) {
        return count(dataSource, """
                select count(*) from task.outbox o join task.instance i on i.id = o.partition_key
                 where i.tenant = ? and o.published_at is null
                """, tenant);
    }

    private String lastError(long id) {
        String error = lastErrorOrNull(id);
        assertNotNull(error);
        return error;
    }

    private String lastErrorOrNull(long id) {
        return OutboxFixture.text(dataSource, "select last_error from task.outbox where id = ?", id);
    }
}
