package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Starts and reads workflow instances (ADR 0019, Start and Query). It is the only writer of the registry's lifecycle
 * status on the start path; the engine is an executor it calls.
 *
 * <p>Start, in order:
 * <ol>
 *   <li>Insert the row in status STARTING, unless the tenant and key already have one. Commit. A repeated key returns
 *       the existing row and nothing else happens, so two identical concurrent requests start one engine instance.</li>
 *   <li>Prepare the engine instance and record its id on the row. The id is stored before the engine runs anything.</li>
 *   <li>Start the engine instance. Return when it is accepted, never when it finishes. Record RUNNING.</li>
 * </ol>
 * A failure in step 2 or 3 is recorded as a start attempt: the row stays STARTING, with the attempt count and the
 * class name of the error. The reconciler ({@link Reconciler}) finds such rows and repairs them.
 *
 * <p>The engine's observer ({@link EngineStatusSync}) can move the row while this service is still in step 3, because
 * the engine may finish or wait before {@code start()} returns. When the row has moved, this service keeps the state
 * the observer recorded and does not overwrite it (WP-105c).
 */
@ApplicationScoped
public class InstanceService {

    /** The actor of changes that the runtime makes by itself. */
    public static final Subject RUNTIME_ACTOR = new Subject(SubjectKind.SERVICE, "agentic-runtime");

    static final String REASON_REQUESTED = "start.requested";
    static final String REASON_PREPARED = "engine.instance.prepared";
    static final String REASON_ACCEPTED = "engine.start.accepted";
    static final String REASON_START_FAILED = "engine.start.failed";

    private static final Logger LOG = Logger.getLogger(InstanceService.class);

    private final InstanceStore store;
    private final WorkflowEngine engine;
    private final LifecycleFaults faults;
    private final Clock clock;
    private final UlidGenerator ids;

    @Inject
    public InstanceService(InstanceStore store, WorkflowEngine engine, LifecycleFaults faults) {
        this(store, engine, faults, Clock.systemUTC(), UlidGenerator.shared());
    }

    InstanceService(InstanceStore store, WorkflowEngine engine, LifecycleFaults faults, Clock clock,
                    UlidGenerator ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Starts an instance, or returns the one that the same idempotency key already created.
     *
     * @throws DefinitionNotFoundException    if the engine does not hold the pinned definition; no row is created
     * @throws IdempotencyKeyReusedException  if the key was used for a different request
     * @throws StoreUnavailableException      if the registry cannot be written; the outcome is then unknown
     */
    public StartResult start(StartRequest request) {
        return start(request, null);
    }

    /**
     * As {@link #start(StartRequest)}. When this call creates the row, {@code onCreate} runs in the same transaction as
     * the insert (see {@link InstanceStore#insertIfAbsent(InstanceRecord, String, InstanceStore.InsertHook)}); if it
     * fails, no instance is created. It does not run when the key already had a row.
     */
    public StartResult start(StartRequest request, InstanceStore.InsertHook onCreate) {
        Objects.requireNonNull(request, "request");
        if (!engine.hasDefinition(request.definition())) {
            throw new DefinitionNotFoundException(request.definition());
        }
        InstanceRecord candidate = InstanceRecord.starting(ids.next(), request, now());
        InstanceStore.Insertion insertion = store.insertIfAbsent(candidate, REASON_REQUESTED, onCreate);
        InstanceRecord row = insertion.record();
        if (!insertion.created()) {
            if (!row.requestFingerprint().equals(request.fingerprint())) {
                throw new IdempotencyKeyReusedException(row.id());
            }
            return new StartResult(row, false);
        }
        faults.hit(LifecycleFaults.AFTER_INSERT);
        return launch(row, request);
    }

    /** The instance with this id in this tenant. Reads only the registry, never the engine's tables. */
    public Optional<InstanceRecord> get(String tenant, Ulid id) {
        return store.find(tenant, id);
    }

    /** The lifecycle history of an instance, oldest first. */
    public List<HistoryEntry> history(Ulid id) {
        return store.history(id);
    }

    private StartResult launch(InstanceRecord row, StartRequest request) {
        WorkflowEngine.EngineLaunch launch;
        try {
            launch = engine.prepare(request.definition(), request.input());
        } catch (RuntimeException e) {
            return new StartResult(recordStartFailure(row, e), true);
        }
        InstanceRecord prepared;
        try {
            prepared = store.replace(
                    row.successor(now(), InstanceStatus.STARTING, launch.engineInstanceId(), row.startAttempts(),
                            row.lastError(), row.waitDeadline()),
                    row.version(), REASON_PREPARED, RUNTIME_ACTOR);
        } catch (StaleVersionException moved) {
            // An operator changed the row while the engine was being prepared (WP-105d, a cancel). The prepared
            // instance is never started: the row says what the operator decided. The response reports that state.
            LOG.infof("instance %s changed while its engine instance was prepared; not started", row.id());
            return new StartResult(store.find(row.tenant(), row.id()).orElse(row), true);
        }
        faults.hit(LifecycleFaults.AFTER_PREPARE);
        try {
            launch.start();
        } catch (RuntimeException e) {
            return new StartResult(recordStartFailure(prepared, e), true);
        }
        faults.hit(LifecycleFaults.BEFORE_ACCEPT);
        InstanceRecord running = recordAccepted(prepared);
        return new StartResult(running, true);
    }

    /**
     * Records RUNNING for an accepted start. If the engine observer already moved the row, its state stands. The
     * returned record is the state this request accepted, so the response reports the acceptance (202, RUNNING).
     */
    private InstanceRecord recordAccepted(InstanceRecord prepared) {
        InstanceRecord next = prepared.successor(now(), InstanceStatus.RUNNING, prepared.engineInstanceId(),
                prepared.startAttempts(), null, prepared.waitDeadline());
        try {
            store.replace(next, prepared.version(), REASON_ACCEPTED, RUNTIME_ACTOR);
        } catch (StaleVersionException moved) {
            LOG.debugf("instance %s moved by the engine observer before the start was recorded", prepared.id());
        }
        return next;
    }

    private InstanceRecord recordStartFailure(InstanceRecord row, RuntimeException cause) {
        String error = cause.getClass().getSimpleName();
        if (error.isEmpty()) {
            error = cause.getClass().getName();
        }
        LOG.warnf(cause, "engine start attempt %d failed for instance %s", row.startAttempts() + 1, row.id());
        InstanceRecord next = row.successor(now(), InstanceStatus.STARTING, row.engineInstanceId(),
                row.startAttempts() + 1, truncate(error), row.waitDeadline());
        try {
            return store.replace(next, row.version(), REASON_START_FAILED, RUNTIME_ACTOR);
        } catch (StaleVersionException moved) {
            LOG.debugf("instance %s moved by the engine observer before the failure was recorded", row.id());
            return next;
        }
    }

    private Instant now() {
        // Postgres keeps microseconds; truncating here makes the values read back equal to the values written.
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String truncate(String text) {
        return text.length() <= InstanceRecord.MAX_ERROR_LENGTH
                ? text
                : text.substring(0, InstanceRecord.MAX_ERROR_LENGTH);
    }
}
