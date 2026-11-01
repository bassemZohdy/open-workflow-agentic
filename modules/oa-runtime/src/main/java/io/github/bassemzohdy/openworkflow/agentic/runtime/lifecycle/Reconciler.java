package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.quarkiverse.flow.internal.WorkflowApplicationReadyEvent;
import io.quarkus.runtime.Startup;
import jakarta.annotation.Priority;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * The startup and periodic reconciler (ADR 0019, Resume and Retries; ADR 0023, decision 7; WP-105c).
 *
 * <p><b>When it runs.</b> It starts only after Flow has warmed its definitions and restored the waiting instances. The
 * signal is Flow's {@link WorkflowApplicationReadyEvent}. Flow's own restore observes that event at the default
 * priority, so this observer uses a later priority and runs after the restore. A warm-up that never finishes is
 * reported after {@code agentic.reconciler.warmup-timeout}; nothing is sleeping on a guessed delay.
 *
 * <p><b>Concurrency.</b> Several nodes may run it. Each registry row is repaired under a Postgres session advisory lock,
 * taken on a connection of its own and released after the repair. A second reconciler that cannot take the lock skips
 * the row. The lock is never held while a row lock is taken on the same thread, and the engine is never called inside
 * a database transaction, so an engine report on the same thread cannot wait on a lock this thread holds.
 *
 * <p><b>Repairs.</b> See docs/lifecycle.md, section Reconciler, for the table of conditions, actions and tests.
 */
@Singleton
@Startup
public class Reconciler {

    private static final Logger LOG = Logger.getLogger(Reconciler.class);
    private static final Duration AUDIT_WINDOW = Duration.ofHours(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    /** The history reason of an instance that waits for a person to renew its delegated authority. */
    public static final String REASON_REAUTHORIZATION = "reauthorization.required";

    /** Where the reconciler is in its life. */
    public enum State {
        /** Disabled by configuration. Only {@link #reconcileOnce()} runs passes. */
        DISABLED,
        /** Waiting for Flow's definition warm-up. */
        AWAITING_WARMUP,
        /** Warm-up did not finish within the timeout. Nothing is repaired. */
        WARMUP_TIMED_OUT,
        /** Running passes. */
        RUNNING
    }

    /** The counts of one pass. */
    public static final class ReconcileReport {
        private final Map<String, Integer> counts = new TreeMap<>();

        void bump(String key) {
            counts.merge(key, 1, Integer::sum);
        }

        /** How many times a condition was seen or a repair made in the pass. */
        public int count(String key) {
            return counts.getOrDefault(key, 0);
        }

        /** Every count of the pass, by key, in key order. Read-only. */
        public Map<String, Integer> counts() {
            return java.util.Collections.unmodifiableMap(counts);
        }

        @Override
        public String toString() {
            return counts.toString();
        }
    }

    private final InstanceStore store;
    private final WorkflowEngine engine;
    private final EngineStatusSync sync;
    private final RegistryScan scan;
    private final RepairStore repairs;
    private final LifecycleSettings settings;
    private final DataSource dataSource;
    private final Clock clock;
    private final Predicate<String> tenantScope;
    private final ResumeGate gate;
    private final RestoreCheckpointGate checkpoints;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean warmedUp = new AtomicBoolean();
    private volatile State state;

    @Inject
    public Reconciler(InstanceStore store, WorkflowEngine engine, EngineStatusSync sync, RegistryScan scan,
                      RepairStore repairs, LifecycleSettings settings, DataSource dataSource, ResumeGate gate,RestoreCheckpointGate checkpoints) {
        this(store, engine, sync, scan, repairs, settings, dataSource, Clock.systemUTC(), tenant -> true, gate,checkpoints);
    }

    public Reconciler(InstanceStore store,WorkflowEngine engine,EngineStatusSync sync,RegistryScan scan,
                      RepairStore repairs,LifecycleSettings settings,DataSource dataSource,ResumeGate gate) {
        this(store,engine,sync,scan,repairs,settings,dataSource,Clock.systemUTC(),tenant->true,gate,RestoreCheckpointGate.ALLOW);
    }

    /**
     * The same reconciler with an explicit clock and a tenant filter. Tests use the filter to limit a pass to the
     * rows they created; production uses the container constructor.
     */
    public Reconciler(InstanceStore store, WorkflowEngine engine, EngineStatusSync sync, RegistryScan scan,
                      RepairStore repairs, LifecycleSettings settings, DataSource dataSource, Clock clock,
                      Predicate<String> tenantScope) {
        this(store, engine, sync, scan, repairs, settings, dataSource, clock, tenantScope, ResumeGate.ALLOW);
    }

    /** As above, with the gate that decides whether a restored instance may run again (WP-102). */
    public Reconciler(InstanceStore store, WorkflowEngine engine, EngineStatusSync sync, RegistryScan scan,
                      RepairStore repairs, LifecycleSettings settings, DataSource dataSource, Clock clock,
                      Predicate<String> tenantScope, ResumeGate gate) {
        this(store,engine,sync,scan,repairs,settings,dataSource,clock,tenantScope,gate,RestoreCheckpointGate.ALLOW);
    }

    public Reconciler(InstanceStore store,WorkflowEngine engine,EngineStatusSync sync,RegistryScan scan,
                      RepairStore repairs,LifecycleSettings settings,DataSource dataSource,Clock clock,
                      Predicate<String> tenantScope,ResumeGate gate,RestoreCheckpointGate checkpoints) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.checkpoints=Objects.requireNonNull(checkpoints,"checkpoints");
        this.store = Objects.requireNonNull(store, "store");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sync = Objects.requireNonNull(sync, "sync");
        this.scan = Objects.requireNonNull(scan, "scan");
        this.repairs = Objects.requireNonNull(repairs, "repairs");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenantScope = Objects.requireNonNull(tenantScope, "tenantScope");
        this.state = settings.reconcilerEnabled ? State.AWAITING_WARMUP : State.DISABLED;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agentic-reconciler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Arms the warm-up watchdog. The reconciler is a startup bean, so this runs when the application starts. */
    @PostConstruct
    void armWatchdog() {
        if (state == State.AWAITING_WARMUP) {
            executor.schedule(this::warmupWatchdog, settings.warmupTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** Where the reconciler is in its life. */
    public State state() {
        return state;
    }

    /**
     * Runs after Flow's restore, because of the later priority. Starts the startup pass on the reconciler's own
     * thread, so the warm-up thread is not held by repairs.
     */
    @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + 1000)
    void onWorkflowReady(@Observes WorkflowApplicationReadyEvent event) {
        if (state == State.DISABLED || !warmedUp.compareAndSet(false, true)) {
            return;
        }
        LOG.infof("lifecycle reconciler: definitions warmed up and restored (application %s)", event.id());
        executor.execute(this::startupPass);
    }

    private void warmupWatchdog() {
        if (!warmedUp.get()) {
            state = State.WARMUP_TIMED_OUT;
            LOG.errorf("lifecycle reconciler: definitions did not warm up within %s; no repairs will run",
                    settings.warmupTimeout);
        }
    }

    private void startupPass() {
        try {
            ReconcileReport report = reconcileOnce();
            LOG.infof("lifecycle reconciler: startup pass complete %s", report);
        } catch (RuntimeException failed) {
            LOG.error("lifecycle reconciler: startup pass failed; the periodic pass will retry", failed);
        } finally {
            state = State.RUNNING;
            executor.scheduleWithFixedDelay(this::periodicPass, settings.interval.toMillis(),
                    settings.interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void periodicPass() {
        try {
            ReconcileReport report = reconcileOnce();
            if (report.counts.values().stream().anyMatch(n -> n > 0)) {
                LOG.infof("lifecycle reconciler: periodic pass %s", report);
            }
        } catch (RuntimeException failed) {
            LOG.error("lifecycle reconciler: periodic pass failed", failed);
        }
    }

    /**
     * Operator entry point for 105d ("retry"): retries the start of one STARTING row now. The staleness and backoff
     * checks are skipped, because an operator asked. Other statuses are left alone.
     */
    public ReconcileReport retry(String tenant, String instanceId) {
        ReconcileReport report = new ReconcileReport();
        RegistryScan.Ref ref = new RegistryScan.Ref(instanceId, tenant);
        Instant now = clock.instant();
        report.bump(withInstance(instanceId, () -> repairStarting(ref, now, true)) ? "retry.examined" : "busy");
        return report;
    }

    /**
     * Operator entry point for 105d ("recover"): repairs one row by its status, as a pass would, without the
     * staleness checks. STARTING rows are started or failed; WAITING rows past their deadline are cancelled or failed,
     * otherwise WAITING and RUNNING rows are resumed or followed; CANCEL_REQUESTED rows are sent to the engine again.
     */
    public ReconcileReport recover(String tenant, String instanceId) {
        ReconcileReport report = new ReconcileReport();
        RegistryScan.Ref ref = new RegistryScan.Ref(instanceId, tenant);
        Instant now = clock.instant();
        report.bump(withInstance(instanceId, () -> {
            switch (load(ref).status()) {
                case STARTING -> repairStarting(ref, now, true);
                case WAITING -> {
                    repairDeadline(ref, now);
                    repairLifetime(ref, now);
                    repairRegistryRow(ref, now);
                }
                case RUNNING -> {
                    repairLifetime(ref, now);
                    repairRegistryRow(ref, now);
                }
                case CANCEL_REQUESTED -> repairCancel(ref, now, true);
                default -> {
                    // Terminal rows never change.
                }
            }
        }) ? "recover.examined" : "busy");
        return report;
    }

    /**
     * One full pass over the registry. Safe to call at any time, from tests or from another thread; rows that another
     * reconciler is repairing are skipped.
     */
    public ReconcileReport reconcileOnce() {
        ReconcileReport report = new ReconcileReport();
        Instant now = clock.instant();
        startingRows(report, now);
        deadlinesPassed(report, now);
        lifetimesExceeded(report, now);
        registryRows(report, now);
        unregisteredEngineInstances(report, now);
        stuckCancels(report, now);
        agentRuns(report, now);
        return report;
    }

    // ---- (i) STARTING rows: retry the start, bounded, or fail the row ---------------------------------------------

    private void startingRows(ReconcileReport report, Instant now) {
        Instant stale = now.minus(settings.startStaleAfter);
        for (RegistryScan.Ref ref : scan.staleInStatus(EnumSet.of(InstanceStatus.STARTING), stale, settings.batchSize)) {
            if (!tenantScope.test(ref.tenant())) {
                continue;
            }
            report.bump(withInstance(ref.id(), () -> repairStarting(ref, now, false)) ? "starting.examined" : "busy");
        }
    }

    private void repairStarting(RegistryScan.Ref ref, Instant now, boolean force) {
        InstanceRecord row = load(ref);
        if (row.status() != InstanceStatus.STARTING) {
            return;
        }
        if (!force && !row.updatedAt().isBefore(now.minus(settings.startStaleAfter))) {
            return;
        }
        if (row.engineInstanceId() != null) {
            Optional<WorkflowEngine.ActiveInstance> view = engine.activeInstance(row.definition(), row.engineInstanceId());
            if (view.isPresent()) {
                adopt(row, view.get());
                return;
            }
        }
        if (row.startAttempts() >= settings.startMaxAttempts) {
            fail(row, now, "start.exhausted");
            return;
        }
        if (!force && row.startAttempts() > 0 && row.updatedAt().plus(backoff(row.startAttempts())).isAfter(now)) {
            return;
        }
        if (row.engineInstanceId() == null) {
            prepareAndStart(row, now);
        } else {
            recordMissingEngineInstance(row, now);
        }
    }

    /** The engine holds the instance: the start was accepted before the registry recorded it. */
    private void adopt(InstanceRecord row, WorkflowEngine.ActiveInstance view) {
        if (view.status() == EngineStatus.PENDING) {
            // Prepared in memory and not started: the start never reached the engine. Start it now.
            if(!prepareResume(row,clock.instant())) return;
            if (engine.startRestored(row.definition(), row.engineInstanceId())) {
                repairs.record("start", row.id().value(), "started", "adopted pending instance");
                sync.observe(row.engineInstanceId(), EngineStatus.RUNNING, "started");
            }
            return;
        }
        sync.observe(row.engineInstanceId(), view.status(), "adopted");
    }

    private void prepareAndStart(InstanceRecord row, Instant now) {
        WorkflowEngine.EngineLaunch launch;
        try {
            launch = engine.prepare(row.definition(), row.input());
        } catch (RuntimeException failed) {
            recordStartFailure(row, now, failed);
            return;
        }
        InstanceRecord prepared;
        try {
            prepared = store.replace(
                    row.successor(now, InstanceStatus.STARTING, launch.engineInstanceId(), row.startAttempts(),
                            row.lastError(), null),
                    row.version(), "reconciler.prepared", InstanceService.RUNTIME_ACTOR);
        } catch (StaleVersionException moved) {
            // An operator changed the row while the engine was prepared (for example a cancel, ADR 0019, amendment 4).
            // The prepared engine instance is never started; the row says what the operator decided.
            repairs.record("start", row.id().value(), "superseded", "row changed while prepared; not started");
            return;
        }
        try {
            launch.start();
        } catch (RuntimeException failed) {
            recordStartFailure(prepared, now, failed);
            return;
        }
        repairs.record("start", row.id().value(), "started", "prepared by reconciler, engine id " + launch.engineInstanceId());
        sync.observe(launch.engineInstanceId(), EngineStatus.RUNNING, "started");
    }

    private void recordStartFailure(InstanceRecord row, Instant now, RuntimeException failed) {
        String error = simpleName(failed);
        store.replace(row.successor(now, InstanceStatus.STARTING, row.engineInstanceId(), row.startAttempts() + 1,
                error, null), row.version(), "reconciler.start.failed", InstanceService.RUNTIME_ACTOR);
        repairs.record("start", row.id().value(), "attempt-failed", error);
    }

    private void recordMissingEngineInstance(InstanceRecord row, Instant now) {
        store.replace(row.successor(now, InstanceStatus.STARTING, row.engineInstanceId(), row.startAttempts() + 1,
                "NoEngineInstance", null), row.version(), "reconciler.start.unconfirmed", InstanceService.RUNTIME_ACTOR);
        repairs.record("start", row.id().value(), "unconfirmed", "engine holds no instance for the recorded id");
    }

    private void fail(InstanceRecord row, Instant now, String why) {
        store.replace(row.successor(now, InstanceStatus.FAILED, row.engineInstanceId(), row.startAttempts(),
                row.lastError(), null), row.version(), "reconciler." + why, InstanceService.RUNTIME_ACTOR);
        repairs.record("start", row.id().value(), "failed", why + " attempts=" + row.startAttempts()
                + " last=" + row.lastError());
    }

    private Duration backoff(int attempts) {
        Duration backoff = settings.startBackoffBase.multipliedBy(1L << Math.min(attempts - 1, 10));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    // ---- (vi) waits whose stored deadline has passed --------------------------------------------------------------

    private void deadlinesPassed(ReconcileReport report, Instant now) {
        for (RegistryScan.Ref ref : scan.waitsPastDeadline(now, settings.batchSize)) {
            if (!tenantScope.test(ref.tenant())) {
                continue;
            }
            report.bump(withInstance(ref.id(), () -> repairDeadline(ref, now)) ? "deadline.examined" : "busy");
        }
    }

    private void repairDeadline(RegistryScan.Ref ref, Instant now) {
        InstanceRecord row = load(ref);
        if (row.status() != InstanceStatus.WAITING || row.waitDeadline() == null || !row.waitDeadline().isBefore(now)) {
            return;
        }
        cancelOverdue(row, now, "deadline", "reconciler.deadline.no-engine-instance", "reconciler.deadline.passed",
                "deadline " + row.waitDeadline() + " passed");
    }

    // ---- (vi-b) instances past their maximum lifetime --------------------------------------------------------------

    private void lifetimesExceeded(ReconcileReport report, Instant now) {
        if (settings.maxLifetime.isEmpty()) {
            return;
        }
        for (RegistryScan.Ref ref : scan.livePastLifetime(now.minus(settings.maxLifetime.get()), settings.batchSize)) {
            if (!tenantScope.test(ref.tenant())) {
                continue;
            }
            report.bump(withInstance(ref.id(), () -> repairLifetime(ref, now)) ? "lifetime.examined" : "busy");
        }
    }

    /**
     * Cancels a RUNNING or WAITING instance created longer ago than the configured maximum lifetime, exactly as a
     * passed wait deadline is cancelled (the request is recorded first, a refused cancel is re-sent by the next pass,
     * an instance the engine no longer holds is failed). The age is judged on the row as loaded now, never on the scan.
     */
    private void repairLifetime(RegistryScan.Ref ref, Instant now) {
        if (settings.maxLifetime.isEmpty()) {
            return;
        }
        InstanceRecord row = load(ref);
        if ((row.status() != InstanceStatus.RUNNING && row.status() != InstanceStatus.WAITING)
                || !row.createdAt().isBefore(now.minus(settings.maxLifetime.get()))) {
            return;
        }
        cancelOverdue(row, now, "lifetime", "reconciler.lifetime.no-engine-instance", "reconciler.lifetime.exceeded",
                "created " + row.createdAt() + ", maximum lifetime " + settings.maxLifetime.get() + " exceeded");
    }

    private void cancelOverdue(InstanceRecord row, Instant now, String kind, String noEngineReason, String cancelReason,
                               String detail) {
        String engineId = row.engineInstanceId();
        if (engineId == null || engine.activeInstance(row.definition(), engineId).isEmpty()) {
            store.replace(row.successor(now, InstanceStatus.FAILED, engineId, row.startAttempts(), row.lastError(), null),
                    row.version(), noEngineReason, InstanceService.RUNTIME_ACTOR);
            repairs.record(kind, row.id().value(), "failed", detail + " and the engine holds no instance");
            return;
        }
        // Record the request first: the engine's CANCELLED report then finds CANCEL_REQUESTED, not the old status.
        InstanceRecord requested = store.replace(
                row.successor(now, InstanceStatus.CANCEL_REQUESTED, engineId, row.startAttempts(), row.lastError(), null),
                row.version(), cancelReason, InstanceService.RUNTIME_ACTOR);
        boolean accepted = engine.cancel(row.definition(), engineId);
        repairs.record(kind, row.id().value(), accepted ? "cancel-requested" : "cancel-refused", detail);
        if (!accepted) {
            LOG.warnf("engine refused the cancel of %s (%s); the cancel is re-sent by the next pass", requested.id(), kind);
        }
    }

    // ---- (ii) RUNNING and WAITING rows: resume restored instances, or follow the engine ---------------------------

    private void registryRows(ReconcileReport report, Instant now) {
        String cursor = "";
        while (true) {
            var page = scan.page(EnumSet.of(InstanceStatus.RUNNING, InstanceStatus.WAITING), cursor, settings.batchSize);
            if (page.isEmpty()) {
                return;
            }
            for (RegistryScan.Ref ref : page) {
                cursor = ref.id();
                if (!tenantScope.test(ref.tenant())) {
                    continue;
                }
                report.bump(withInstance(ref.id(), () -> repairRegistryRow(ref, now)) ? "running.examined" : "busy");
            }
            if (page.size() < settings.batchSize) {
                return;
            }
        }
    }

    private void repairRegistryRow(RegistryScan.Ref ref, Instant now) {
        InstanceRecord row = load(ref);
        if (row.status() != InstanceStatus.RUNNING && row.status() != InstanceStatus.WAITING) {
            return;
        }
        String engineId = row.engineInstanceId();
        if (engineId == null) {
            return;
        }
        Optional<WorkflowEngine.ActiveInstance> view = engine.activeInstance(row.definition(), engineId);
        if (view.isEmpty()) {
            auditOnce("instance.absent", row.id().value(), "unknown-outcome", "status " + row.status()
                    + "; the engine holds no instance and its outcome is not recorded", now);
            return;
        }
        if (view.get().status() == EngineStatus.PENDING) {
            // Restored from Flow's tables and registered as active, but not started (WP-003, fact 1).
            // First ask whether the authority of the instance still holds (WP-102): a restored instance that runs under
            // a delegation grant continues only while the grant is usable. Anything but PROCEED keeps it from running.
            if(!prepareResume(row,now)) return;
            boolean wasHeld = isHeldForAuthority(row);
            // The epoch is written first, so the task that runs again sees the new value.
            long epoch = repairs.incrementResumeEpoch(row.id().value());
            boolean started = engine.startRestored(row.definition(), engineId);
            repairs.record("resume", row.id().value(), started ? "resumed" : "refused", "resume epoch " + epoch);
            if (started && wasHeld) {
                sync.observe(engineId, EngineStatus.RUNNING, "reauthorized");
            }
            return;
        }
        sync.observe(engineId, view.get().status(), "polled");
    }

    /** Identity is checked before and after receipt I/O, and neither a partial batch nor an error releases work. */
    private boolean prepareResume(InstanceRecord row,Instant now) {
        var decision=gateDecision(row);
        if(!decision.proceeds()) { holdResume(row,decision,now);return false; }
        try {
            if(!checkpoints.prepare(row)) {
                holdForAuthority(row,new ResumeGate.Decision(ResumeGate.Outcome.UNAVAILABLE,"checkpoint.receipt.pending"),now);
                return false;
            }
            var current=store.find(row.tenant(),row.id()).orElseThrow();
            if(!resumable(row,current)) return false;
            decision=gateDecision(current); // The receipt audit/storage work may have outlasted the prior grant check.
            if(!decision.proceeds()) { holdResume(current,decision,clock.instant());return false; }
            return resumable(row,store.find(row.tenant(),row.id()).orElseThrow()); // Cancellation may race the authority read too.
        } catch(RuntimeException failed) {
            LOG.warnf("receipt restore gate failed for instance %s (%s); the instance is not resumed",row.id(),simpleName(failed));
            holdForAuthority(row,new ResumeGate.Decision(ResumeGate.Outcome.UNAVAILABLE,"checkpoint.receipt.unavailable"),now);
            return false;
        }
    }

    private static boolean resumable(InstanceRecord expected,InstanceRecord current) {
        return Set.of(InstanceStatus.STARTING,InstanceStatus.RUNNING,InstanceStatus.WAITING).contains(current.status())
                && Objects.equals(expected.engineInstanceId(),current.engineInstanceId()) && expected.definition().equals(current.definition())
                && expected.owner().equals(current.owner());
    }

    private void holdResume(InstanceRecord row,ResumeGate.Decision decision,Instant now) {
        if(row.status()==InstanceStatus.STARTING && decision.outcome()==ResumeGate.Outcome.REAUTHORIZATION_REQUIRED) {
            // A prepared but never-started instance cannot transition STARTING -> WAITING. Its identity authority
            // is already held by the identity gate; keep the registry phase and refuse the first execution.
            auditOnce("start",row.id().value(),"held","authority unavailable before first execution: "+decision.reason(),now);
        } else holdForAuthority(row,decision,now);
    }

    /** The gate's answer; a gate that throws is UNAVAILABLE, so the instance does not run. */
    private ResumeGate.Decision gateDecision(InstanceRecord row) {
        try {
            return gate.check(row);
        } catch (RuntimeException failed) {
            LOG.warnf("resume gate failed for instance %s (%s); the instance is not resumed now", row.id(),
                    simpleName(failed));
            return new ResumeGate.Decision(ResumeGate.Outcome.UNAVAILABLE, "gate.failed");
        }
    }

    /**
     * The instance must not run. For REAUTHORIZATION_REQUIRED the registry says so: a RUNNING row moves to WAITING, and
     * a history row with the reason {@value #REASON_REAUTHORIZATION} is written once (it reaches the task view and the
     * inspect output). The wait gets the usual deadline, so an instance that nobody re-authorizes is cancelled by the
     * deadline repair like any other wait. An UNAVAILABLE gate changes nothing and is reported once per audit window.
     */
    private void holdForAuthority(InstanceRecord row, ResumeGate.Decision decision, Instant now) {
        if (decision.outcome() == ResumeGate.Outcome.UNAVAILABLE) {
            auditOnce("resume", row.id().value(), "held", "authority unavailable: " + decision.reason(), now);
            return;
        }
        if (!isHeldForAuthority(row)) {
            Instant deadline = row.waitDeadline() != null ? row.waitDeadline() : now.plus(settings.waitDeadline);
            try {
                store.replace(row.successor(now, InstanceStatus.WAITING, row.engineInstanceId(), row.startAttempts(),
                        row.lastError(), deadline), row.version(), REASON_REAUTHORIZATION, InstanceService.RUNTIME_ACTOR);
            } catch (StaleVersionException moved) {
                LOG.debugf("instance %s changed while it was held for re-authorization; the next pass decides", row.id());
                return;
            }
        }
        auditOnce("resume", row.id().value(), "held", "re-authorization required: " + decision.reason(), now);
    }

    private boolean isHeldForAuthority(InstanceRecord row) {
        if (row.status() != InstanceStatus.WAITING) {
            return false;
        }
        java.util.List<HistoryEntry> history = store.history(row.id());
        return !history.isEmpty() && REASON_REAUTHORIZATION.equals(history.get(history.size() - 1).reason());
    }

    // ---- (iii) engine instances the registry does not know --------------------------------------------------------

    private void unregisteredEngineInstances(ReconcileReport report, Instant now) {
        for (DefinitionRef definition : engine.definitions()) {
            for (String engineId : engine.activeInstanceIds(definition)) {
                if (scan.byEngineInstanceId(engineId).isPresent()) {
                    continue;
                }
                report.bump("unregistered");
                auditOnce("engine.unregistered", engineId, "reported",
                        definition.definitionId() + "@" + definition.version()
                                + "; not cancelled: the engine instance carries no tenant or key", now);
            }
        }
    }

    // ---- (iv) CANCEL_REQUESTED rows that are not confirmed --------------------------------------------------------

    private void stuckCancels(ReconcileReport report, Instant now) {
        Instant stale = now.minus(settings.cancelReissueAfter);
        for (RegistryScan.Ref ref : scan.staleInStatus(EnumSet.of(InstanceStatus.CANCEL_REQUESTED), stale, settings.batchSize)) {
            if (!tenantScope.test(ref.tenant())) {
                continue;
            }
            report.bump(withInstance(ref.id(), () -> repairCancel(ref, now, false)) ? "cancel.examined" : "busy");
        }
    }

    private void repairCancel(RegistryScan.Ref ref, Instant now, boolean force) {
        InstanceRecord row = load(ref);
        if (row.status() != InstanceStatus.CANCEL_REQUESTED) {
            return;
        }
        if (!force && !row.updatedAt().isBefore(now.minus(settings.cancelReissueAfter))) {
            return;
        }
        String engineId = row.engineInstanceId();
        if (engineId == null) {
            // Not reachable for rows written since ADR 0019 amendment 4 (issue #24): a cancel of a row with no engine
            // id goes straight to CANCELLED. Kept for rows that were already CANCEL_REQUESTED without an engine id.
            auditOnce("cancel.unconfirmed", row.id().value(), "no-engine-instance",
                    "cancel requested before the engine id was recorded", now);
            return;
        }
        Optional<WorkflowEngine.ActiveInstance> view = engine.activeInstance(row.definition(), engineId);
        if (view.isEmpty()) {
            // Only the engine's own report may complete the cancel (ADR 0019). Until then the row waits.
            auditOnce("cancel.unconfirmed", row.id().value(), "awaiting-engine-report",
                    "the engine holds no instance; CANCELLED waits for its report", now);
            return;
        }
        if (view.get().status() != EngineStatus.PENDING && view.get().status() != EngineStatus.RUNNING
                && view.get().status() != EngineStatus.WAITING) {
            sync.observe(engineId, view.get().status(), "polled");
            return;
        }
        boolean accepted = engine.cancel(row.definition(), engineId);
        repairs.record("cancel", row.id().value(), accepted ? "reissued" : "refused", "engine status " + view.get().status());
    }

    // ---- (v) agent runs without a live process --------------------------------------------------------------------

    private void agentRuns(ReconcileReport report, Instant now) {
        for (RegistryScan.AgentRun run : scan.agentRunsWithoutLiveLease(now, settings.batchSize)) {
            AgentRunPolicy.Decision decision = AgentRunPolicy.decide(run.bridge(), false);
            report.bump("agent." + decision.name().toLowerCase());
            auditOnce("agent.run", run.taskId(), decision.name().toLowerCase(),
                    "status " + run.status() + ", bridge " + run.bridge()
                            + (decision == AgentRunPolicy.Decision.RESTART_BY_RECONCILER
                                    ? "; no restarter is registered (WP-201)" : ""), now);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private InstanceRecord load(RegistryScan.Ref ref) {
        return store.find(ref.tenant(), Ulid.parse(ref.id()))
                .orElseThrow(() -> new IllegalStateException("registry row " + ref.id() + " vanished during a pass"));
    }

    private void auditOnce(String kind, String subject, String outcome, String detail, Instant now) {
        if (!repairs.recordedSince(kind, subject, now.minus(AUDIT_WINDOW))) {
            repairs.record(kind, subject, outcome, detail);
        }
    }

    /**
     * Runs the work under the advisory lock of the instance. Returns false when another reconciler holds the lock. The
     * lock lives on its own connection; the work takes its own connections, so the lock never blocks the work.
     */
    private boolean withInstance(String instanceId, Runnable work) {
        Optional<AdvisoryLock> lock;
        try {
            lock = AdvisoryLock.tryAcquire(dataSource, "instance:" + instanceId);
        } catch (SQLException e) {
            throw new StoreUnavailableException("the registry database failed while taking a repair lock", e);
        }
        if (lock.isEmpty()) {
            return false;
        }
        try (AdvisoryLock held = lock.get()) {
            work.run();
            return true;
        } catch (SQLException e) {
            throw new StoreUnavailableException("the registry database failed while releasing a repair lock", e);
        }
    }

    private static String simpleName(RuntimeException failed) {
        String name = failed.getClass().getSimpleName();
        return name.isEmpty() ? failed.getClass().getName() : name;
    }

    /** A session-level Postgres advisory lock on a dedicated connection (released on close). */
    static final class AdvisoryLock implements AutoCloseable {

        private final Connection connection;
        private final long key;

        private AdvisoryLock(Connection connection, long key) {
            this.connection = connection;
            this.key = key;
        }

        static Optional<AdvisoryLock> tryAcquire(DataSource dataSource, String name) throws SQLException {
            long key = key(name);
            Connection connection = dataSource.getConnection();
            boolean acquired = false;
            try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setLong(1, key);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    acquired = rs.getBoolean(1);
                }
                return acquired ? Optional.of(new AdvisoryLock(connection, key)) : Optional.empty();
            } finally {
                if (!acquired) {
                    connection.close();
                }
            }
        }

        @Override
        public void close() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, key);
                statement.executeQuery().close();
            } finally {
                connection.close();
            }
        }

        static long key(String name) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
                return ByteBuffer.wrap(digest, 0, 8).getLong();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
            }
        }
    }
}
