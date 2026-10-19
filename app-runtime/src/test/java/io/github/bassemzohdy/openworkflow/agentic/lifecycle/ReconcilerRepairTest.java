package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.count;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.EngineStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.EngineStatusSync;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.LifecycleSettings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.RegistryScan;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.RepairStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Reconciler;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Each repair of the reconciler (WP-105c, scope items 2 and 4), driven by {@code reconcileOnce()} against the real
 * registry and a fake engine. The reconciler here runs only on the rows of the test's own tenant.
 */
@QuarkusTest
class ReconcilerRepairTest {

    /** Every row counts as stale at once, so one pass acts on the state the test just set up. */
    static final Duration TINY = Duration.ofNanos(1000);

    /**
     * Engine ids are unique in the registry and the database is shared between runs, so the fixed ids of these tests
     * carry a per-run suffix.
     */
    static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @Inject
    JdbcInstanceStore store;

    @Inject
    RegistryScan scan;

    @Inject
    RepairStore repairs;

    @Inject
    EngineStatusSync sync;

    @Inject
    DataSource dataSource;

    private static LifecycleSettings settings(int maxAttempts) {
        return new LifecycleSettings(true, Duration.ofSeconds(30), Duration.ofSeconds(120), TINY, maxAttempts,
                TINY, TINY, Duration.ofHours(24), 100, Optional.empty());
    }

    private Reconciler reconciler(FakeEngine engine, String tenant, int maxAttempts) {
        return new Reconciler(store, engine, sync, scan, repairs, settings(maxAttempts), dataSource,
                Clock.systemUTC(), tenant::equals);
    }

    private InstanceRecord newRow(String tenant) {
        StartRequest request = new StartRequest(tenant, new Subject(SubjectKind.USER, "alice"), FakeEngine.PROBE,
                Map.of("mode", "ok", "effectUrl", "http://127.0.0.1:1/unused"), newKey());
        InstanceRecord candidate = InstanceRecord.starting(UlidGenerator.shared().next(), request,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        return store.insertIfAbsent(candidate, "test.insert").record();
    }

    /** Moves a row through the store, as the start path or the engine observer would. */
    private InstanceRecord move(InstanceRecord row, InstanceStatus status, String engineId, Instant deadline) {
        return store.replace(row.successor(Instant.now().truncatedTo(ChronoUnit.MICROS), status, engineId,
                row.startAttempts(), row.lastError(), deadline), row.version(), "test.setup",
                InstanceService.RUNTIME_ACTOR);
    }

    private InstanceRecord reload(String tenant, InstanceRecord row) {
        return store.find(tenant, row.id()).orElseThrow();
    }

    @Test
    void aStartingRowWithNoEngineIdIsPreparedAndStarted() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = newRow(tenant);

        var report = reconciler(engine, tenant, 3).reconcileOnce();

        InstanceRecord after = reload(tenant, row);
        assertEquals(InstanceStatus.RUNNING, after.status());
        assertNotNull(after.engineInstanceId());
        assertEquals(1, engine.prepares.get());
        assertEquals(1, engine.starts.get());
        assertTrue(report.count("starting.examined") >= 1);
    }

    @Test
    void aStartThatFailedOnceIsRetriedAndThenAccepted() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        engine.failNextStart = new IllegalStateException("simulated");
        InstanceRecord row = newRow(tenant);
        Reconciler reconciler = reconciler(engine, tenant, 3);

        reconciler.reconcileOnce();
        InstanceRecord failed = reload(tenant, row);
        assertEquals(InstanceStatus.STARTING, failed.status());
        assertEquals(1, failed.startAttempts());
        assertEquals("IllegalStateException", failed.lastError());

        reconciler.reconcileOnce();
        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status());
        // The engine kept the prepared instance (PENDING) after the failed start, so the retry starts that object.
        assertEquals(1, engine.starts.get());
        assertEquals(1, engine.restarts.get());
        assertEquals(1, engine.prepares.get(), "a row with an engine id is never prepared again");
    }

    @Test
    void aStartingRowTheEngineNeverHeardOfIsFailedAfterTheLimit() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = newRow(tenant);
        // The engine id was recorded (tx2) and then the process died before start(); the engine holds nothing.
        move(row, InstanceStatus.STARTING, "lost-" + RUN, null);
        Reconciler reconciler = reconciler(engine, tenant, 3);

        for (int pass = 0; pass < 3; pass++) {
            reconciler.reconcileOnce();
        }
        assertEquals(InstanceStatus.STARTING, reload(tenant, row).status());
        assertEquals(3, reload(tenant, row).startAttempts());

        reconciler.reconcileOnce();
        InstanceRecord failed = reload(tenant, row);
        assertEquals(InstanceStatus.FAILED, failed.status());
        assertEquals(0, engine.prepares.get(), "a row with an engine id is never prepared again");
    }

    @Test
    void aPendingRestoredInstanceIsStartedAndItsEpochGoesUp() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        String engineId = "restored-" + RUN;
        engine.report(engineId, EngineStatus.PENDING);
        InstanceRecord row = move(newRow(tenant), InstanceStatus.RUNNING, engineId, null);
        Reconciler reconciler = reconciler(engine, tenant, 3);
        assertEquals(0L, scan.resumeEpoch(row.id().value()).orElseThrow());

        reconciler.reconcileOnce();
        assertEquals(1, engine.restarts.get());
        assertEquals(1L, scan.resumeEpoch(row.id().value()).orElseThrow());
        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status());

        // The engine now reports RUNNING, so a further pass resumes nothing and the epoch stays.
        reconciler.reconcileOnce();
        assertEquals(1, engine.restarts.get());
        assertEquals(1L, scan.resumeEpoch(row.id().value()).orElseThrow());
    }

    @Test
    void aStartingRowTheEngineHoldsAsPendingIsStartedWithoutAnEpochChange() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        String engineId = "pending-start-" + RUN;
        engine.report(engineId, EngineStatus.PENDING);
        InstanceRecord row = move(newRow(tenant), InstanceStatus.STARTING, engineId, null);

        reconciler(engine, tenant, 3).reconcileOnce();

        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status());
        assertEquals(1, engine.restarts.get());
        assertEquals(0L, scan.resumeEpoch(row.id().value()).orElseThrow());
    }

    @Test
    void anEngineInstanceNoRowRegistersIsReportedAndNeverCancelled() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        String stray = engine.adopt(EngineStatus.RUNNING);
        InstanceRecord row = move(newRow(tenant), InstanceStatus.RUNNING, "registered-" + RUN, null);
        engine.report("registered-" + RUN, EngineStatus.RUNNING);

        var report = reconciler(engine, tenant, 3).reconcileOnce();

        assertTrue(report.count("unregistered") >= 1);
        assertEquals(0, engine.cancels.get());
        assertEquals(0, engine.prepares.get());
        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status());
        assertTrue(count(dataSource, "select count(*) from task.repair_event where kind = 'engine.unregistered' and subject = ?",
                stray) >= 1);
    }

    @Test
    void aStuckCancelIsSentAgainAndCompletesOnlyWhenTheEngineReports() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        String engineId = "cancel-" + RUN;
        engine.report(engineId, EngineStatus.RUNNING);
        InstanceRecord row = move(newRow(tenant), InstanceStatus.RUNNING, engineId, null);
        row = move(row, InstanceStatus.CANCEL_REQUESTED, engineId, null);
        Reconciler reconciler = reconciler(engine, tenant, 3);

        reconciler.reconcileOnce();
        assertEquals(1, engine.cancels.get());
        assertEquals(InstanceStatus.CANCEL_REQUESTED, reload(tenant, row).status());

        // The engine ignores the request and still runs the instance: the cancel is sent again, and the registry
        // does not claim CANCELLED from a request alone.
        reconciler.reconcileOnce();
        assertEquals(2, engine.cancels.get());
        assertEquals(InstanceStatus.CANCEL_REQUESTED, reload(tenant, row).status());

        // Only the engine's report completes it: here the engine reports CANCELLED and the poll records it.
        engine.report(engineId, EngineStatus.CANCELLED);
        reconciler.reconcileOnce();
        assertEquals(InstanceStatus.CANCELLED, reload(tenant, row).status());
    }

    @Test
    void aCancelTheEngineNoLongerHoldsWaitsForItsReport() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = move(newRow(tenant), InstanceStatus.RUNNING, "gone-" + RUN, null);
        row = move(row, InstanceStatus.CANCEL_REQUESTED, "gone-" + RUN, null);

        var report = reconciler(engine, tenant, 3).reconcileOnce();

        assertEquals(InstanceStatus.CANCEL_REQUESTED, reload(tenant, row).status());
        assertEquals(0, engine.cancels.get());
        assertTrue(report.count("cancel.examined") >= 1);
        assertTrue(count(dataSource, "select count(*) from task.repair_event where kind = 'cancel.unconfirmed' and subject = ?",
                row.id().value()) >= 1);
    }

    @Test
    void aWaitPastItsDeadlineIsCancelledAndTheRequestIsRecordedFirst() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        String engineId = "deadline-" + RUN;
        engine.report(engineId, EngineStatus.WAITING);
        InstanceRecord row = move(newRow(tenant), InstanceStatus.RUNNING, engineId, null);
        row = move(row, InstanceStatus.WAITING, engineId, Instant.now().minus(Duration.ofMinutes(1)));

        reconciler(engine, tenant, 3).reconcileOnce();

        // The request is recorded before the engine is asked, so the engine's CANCELLED report finds CANCEL_REQUESTED.
        assertTrue(engine.cancels.get() >= 1);
        assertEquals(InstanceStatus.CANCEL_REQUESTED, reload(tenant, row).status());
        sync.observe(engineId, EngineStatus.CANCELLED, "observed");
        assertEquals(InstanceStatus.CANCELLED, reload(tenant, row).status());
    }

    @Test
    void aWaitPastItsDeadlineWithNoEngineInstanceFailsTheRow() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = move(newRow(tenant), InstanceStatus.RUNNING, "absent-" + RUN, null);
        row = move(row, InstanceStatus.WAITING, "absent-" + RUN, Instant.now().minus(Duration.ofMinutes(1)));

        reconciler(engine, tenant, 3).reconcileOnce();

        assertEquals(InstanceStatus.FAILED, reload(tenant, row).status());
        assertEquals(0, engine.cancels.get());
    }

    @Test
    void twoReconcilersNeverActOnTheSameRowAtOnce() throws Exception {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = newRow(tenant);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        engine.startEntered = entered;
        engine.startRelease = release;

        Reconciler first = reconciler(engine, tenant, 3);
        Reconciler second = reconciler(engine, tenant, 3);
        Thread holder = new Thread(first::reconcileOnce, "holder");
        holder.start();
        try {
            assertTrue(entered.await(30, TimeUnit.SECONDS), "the first reconciler never reached the start");

            var report = second.reconcileOnce();

            assertTrue(report.count("busy") >= 1, "the second reconciler must skip the locked row");
            assertEquals(1, engine.prepares.get());
            assertEquals(1, engine.starts.get());
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertFalse(holder.isAlive());
        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status());
        assertEquals(1, engine.prepares.get(), "one engine instance for one row");
        OptionalLong epoch = scan.resumeEpoch(row.id().value());
        assertEquals(0L, epoch.orElseThrow());
    }

    @Test
    void theEngineStatusIsWrittenToTheRowThroughTheObserverPath() {
        String tenant = newTenant();
        InstanceRecord row = move(newRow(tenant), InstanceStatus.RUNNING, "observed-" + RUN, null);

        assertEquals(EngineStatusSync.Result.CHANGED, sync.observe("observed-" + RUN, EngineStatus.WAITING, "observed"));
        assertEquals(InstanceStatus.WAITING, reload(tenant, row).status());
        assertNotNull(reload(tenant, row).waitDeadline());
        assertEquals(EngineStatusSync.Result.UNCHANGED, sync.observe("observed-" + RUN, EngineStatus.WAITING, "observed"));
        assertEquals(EngineStatusSync.Result.NO_ROW, sync.observe("nobody-1", EngineStatus.WAITING, "observed"));
    }

    @Test
    void theOperatorEntryPointsRepairOneRowWithoutTheStalenessChecks() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        // Staleness and backoff of one hour: a pass would leave these rows alone, an operator's call must not.
        Reconciler patient = new Reconciler(store, engine, sync, scan, repairs,
                new LifecycleSettings(true, Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofHours(1), 3,
                        Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofHours(24), 100, Optional.empty()),
                dataSource, Clock.systemUTC(), tenant::equals);

        InstanceRecord starting = move(newRow(tenant), InstanceStatus.STARTING, "retry-" + RUN, null);
        patient.retry(tenant, starting.id().value());
        assertEquals(1, reload(tenant, starting).startAttempts(), "retry counts the failed attempt at once");

        String engineId = "recover-" + RUN;
        engine.report(engineId, EngineStatus.WAITING);
        InstanceRecord waiting = move(move(newRow(tenant), InstanceStatus.RUNNING, engineId, null),
                InstanceStatus.WAITING, engineId, Instant.now().minus(Duration.ofMinutes(1)));
        patient.recover(tenant, waiting.id().value());
        assertEquals(1, engine.cancels.get(), "recover cancels a wait past its deadline");
        assertEquals(InstanceStatus.CANCEL_REQUESTED, reload(tenant, waiting).status());
    }
}
