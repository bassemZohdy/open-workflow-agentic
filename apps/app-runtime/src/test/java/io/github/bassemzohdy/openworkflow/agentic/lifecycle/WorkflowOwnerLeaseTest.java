package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Proposed owner lease against a disposable PostgreSQL database. No production owner wiring is implied. */
@QuarkusTest
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowOwnerLeaseTest {
    @Inject DataSource data;
    @Inject JdbcInstanceStore store;

    @BeforeAll void installProposal() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/proposed/V82__workflow_owner_lease.sql")) {
            assertNotNull(in);
            try (var c = data.getConnection(); var s = c.createStatement()) {
                s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                s.execute("create table task.workflow_lease_test_effect (instance_id text not null)");
            }
        }
    }
    private JdbcWorkflowOwnerLease.Binding binding() {
        String tenant = "tenant-" + UUID.randomUUID(), engine = "engine-" + UUID.randomUUID();
        var actor = new Subject(SubjectKind.USER, "lease-user");
        var definition = new DefinitionRef("agentic-test/lease", "1.0.0");
        var request = new StartRequest(tenant, actor, definition, Map.of(), "lease-" + UUID.randomUUID());
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
        row = store.replace(row.successor(now, InstanceStatus.RUNNING, engine, 0, null, null), row.version(), "test.running", actor);
        return new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), "app-test", engine, definition);
    }

    @Test void concurrentOwnersCannotShareOrReuseAReleaseGeneration() throws Exception {
        var binding = binding(); var first = new JdbcWorkflowOwnerLease(data); var second = new JdbcWorkflowOwnerLease(data);
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var x = pool.submit(() -> { start.await(); return first.acquire(binding, a, Duration.ofSeconds(10)); });
            var y = pool.submit(() -> { start.await(); return second.acquire(binding, b, Duration.ofSeconds(10)); });
            start.countDown();
            var one = x.get(10, TimeUnit.SECONDS); var two = y.get(10, TimeUnit.SECONDS);
            assertNotEquals(one.isPresent(), two.isPresent());
            var winner = one.orElseGet(two::orElseThrow); var other = one.isPresent() ? b : a;
            assertEquals(winner, first.acquire(binding, winner.claim().ownerId(), Duration.ofSeconds(20)).orElseThrow(),
                    "an acquisition retry cannot silently extend authority");
            assertTrue(first.release(winner.claim()));
            var successor = second.acquire(binding, other, Duration.ofSeconds(10)).orElseThrow();
            assertEquals(winner.claim().epoch() + 1, successor.claim().epoch());
            assertFalse(first.release(winner.claim()));
            assertTrue(first.renew(winner.claim(), Duration.ofSeconds(1)).isEmpty());
            assertThrows(IllegalStateException.class, () -> first.withCurrentLease(winner.claim(), c -> null));
            assertEquals("owned", second.withCurrentLease(successor.claim(), c -> "owned"));
        } finally { pool.shutdownNow(); }
    }

    @Test void expiredOwnerCannotRenewAndTakeoverAdvancesTheEpoch() throws Exception {
        var binding = binding(); var leases = new JdbcWorkflowOwnerLease(data); var owner = UUID.randomUUID();
        var old = leases.acquire(binding, owner, Duration.ofMillis(400)).orElseThrow();
        Thread.sleep(650);
        assertTrue(leases.renew(old.claim(), Duration.ofSeconds(2)).isEmpty());
        try (var c = data.getConnection(); var q = c.prepareStatement("update task.workflow_owner_lease "
                + "set renewed_at=clock_timestamp(),expires_at=clock_timestamp()+interval '5 seconds' where instance_id=?")) {
            q.setString(1, binding.instanceId().value());
            assertThrows(SQLException.class, q::executeUpdate, "expired generation cannot be revived by direct SQL");
        }
        var next = leases.acquire(binding, owner, Duration.ofSeconds(5)).orElseThrow();
        assertEquals(old.claim().epoch() + 1, next.claim().epoch());
        assertThrows(IllegalStateException.class, () -> leases.withCurrentLease(old.claim(), c -> null));
        assertFalse(leases.release(old.claim()));
    }

    @Test void expiryDuringDatabaseWorkRollsItBack() {
        var binding = binding(); var leases = new JdbcWorkflowOwnerLease(data);
        var lease = leases.acquire(binding, UUID.randomUUID(), Duration.ofMillis(1500)).orElseThrow();
        var wrote = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> leases.withCurrentLease(lease.claim(), c -> {
            try (var q = c.prepareStatement("insert into task.workflow_lease_test_effect(instance_id) values (?)")) {
                q.setString(1, binding.instanceId().value()); q.executeUpdate(); wrote.set(true);
            }
            try (var q = c.prepareStatement("select pg_sleep(1.8)")) { q.execute(); }
            return null;
        }));
        assertTrue(wrote.get(), "the database write must happen before the lease expires");
        assertEquals(0, countEffects(binding.instanceId().value()));
    }

    @Test void expiryWhileWaitingForTheLeaseRowRefusesTheCallback() throws Exception {
        var binding = binding(); var leases = new JdbcWorkflowOwnerLease(data);
        var lease = leases.acquire(binding, UUID.randomUUID(), Duration.ofMillis(600)).orElseThrow();
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        try (var lock = data.getConnection()) {
            lock.setAutoCommit(false);
            try (var q = lock.prepareStatement("select epoch from task.workflow_owner_lease where instance_id=? for update")) {
                q.setString(1, binding.instanceId().value()); q.executeQuery().close();
            }
            var started = new CountDownLatch(1);
            var waiting = pool.submit(() -> {
                started.countDown();
                return leases.withCurrentLease(lease.claim(), c -> { entered.set(true); return null; });
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(850);
            lock.commit();
            var failure = assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertFalse(entered.get());
        } finally { pool.shutdownNow(); }
    }

    @Test void successorWaitsUntilExpiredOwnersDatabaseWorkRollsBack() throws Exception {
        var binding = binding(); var leases = new JdbcWorkflowOwnerLease(data);
        var lease = leases.acquire(binding, UUID.randomUUID(), Duration.ofMillis(1000)).orElseThrow();
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var old = pool.submit(() -> leases.withCurrentLease(lease.claim(), c -> {
                try (var q = c.prepareStatement("insert into task.workflow_lease_test_effect(instance_id) values (?)")) {
                    q.setString(1, binding.instanceId().value()); q.executeUpdate();
                }
                entered.countDown();
                try { if (!finish.await(4, TimeUnit.SECONDS)) throw new SQLException("test did not release owner"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
                return null;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.sleep(1250);
            var attempting = new CountDownLatch(1);
            var successor = pool.submit(() -> {
                attempting.countDown();
                return leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(5));
            });
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertFalse(successor.isDone(), "takeover must wait for the old transaction to end");
            finish.countDown();
            var failure = assertThrows(ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(lease.claim().epoch() + 1, successor.get(5, TimeUnit.SECONDS).orElseThrow().claim().epoch());
            assertEquals(0, countEffects(binding.instanceId().value()));
        } finally { finish.countDown(); pool.shutdownNow(); }
    }

    @Test void bindingAndRegistryStatusFailClosed() {
        var binding = binding(); var leases = new JdbcWorkflowOwnerLease(data); var owner = UUID.randomUUID();
        var lease = leases.acquire(binding, owner, Duration.ofSeconds(5)).orElseThrow();
        var wrongApp = new JdbcWorkflowOwnerLease.Binding(binding.tenant(), binding.instanceId(), "other-app",
                binding.engineInstanceId(), binding.definition());
        assertThrows(IllegalStateException.class, () -> leases.acquire(wrongApp, UUID.randomUUID(), Duration.ofSeconds(5)));
        var wrongTenant = new JdbcWorkflowOwnerLease.Binding("other-tenant", binding.instanceId(), binding.applicationId(),
                binding.engineInstanceId(), binding.definition());
        assertTrue(leases.acquire(wrongTenant, UUID.randomUUID(), Duration.ofSeconds(5)).isEmpty());
        var row = store.find(binding.tenant(), binding.instanceId()).orElseThrow();
        store.replace(row.successor(Instant.now().truncatedTo(ChronoUnit.MICROS), InstanceStatus.CANCEL_REQUESTED,
                row.engineInstanceId(), row.startAttempts(), null, null), row.version(), "test.cancel", row.owner());
        assertTrue(leases.renew(lease.claim(), Duration.ofSeconds(5)).isEmpty());
        assertThrows(IllegalStateException.class, () -> leases.withCurrentLease(lease.claim(), c -> null));
        assertTrue(leases.release(lease.claim()), "terminal cleanup may release but cannot authorize work");
    }

    @Test void databaseRetainsOwnerGenerationsAndRejectsLiveTakeover() throws Exception {
        var binding = binding(); var leases = new JdbcWorkflowOwnerLease(data);
        var lease = leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(10)).orElseThrow();
        try (var c = data.getConnection(); var q = c.prepareStatement(
                "update task.workflow_owner_lease set owner_id=?,epoch=epoch+1 where instance_id=?")) {
            q.setObject(1, UUID.randomUUID()); q.setString(2, binding.instanceId().value());
            assertThrows(SQLException.class, q::executeUpdate);
        }
        try (var c = data.getConnection(); var q = c.prepareStatement("delete from task.workflow_owner_lease where instance_id=?")) {
            q.setString(1, binding.instanceId().value()); assertThrows(SQLException.class, q::executeUpdate);
        }
        assertEquals("still-current", leases.withCurrentLease(lease.claim(), c -> "still-current"));
    }
    private long countEffects(String id) {
        try (var c = data.getConnection(); var q = c.prepareStatement("select count(*) from task.workflow_lease_test_effect where instance_id=?")) {
            q.setString(1, id); try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
