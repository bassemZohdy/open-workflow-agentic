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
 * status; the engine is an executor it calls.
 *
 * <p>Start, in order:
 * <ol>
 *   <li>Insert the row in status STARTING, unless the tenant and key already have one. Commit. A repeated key returns
 *       the existing row and nothing else happens, so two identical concurrent requests start one engine instance.</li>
 *   <li>Prepare the engine instance and record its id on the row. The id is stored before the engine runs anything.</li>
 *   <li>Start the engine instance. Return when it is accepted, never when it finishes. Record RUNNING.</li>
 * </ol>
 * A failure in step 2 or 3 is recorded as a start attempt: the row stays STARTING, with the attempt count and the
 * class name of the error. The reconciler (WP-105c) finds such rows; this service never starts them again.
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
    private final Clock clock;
    private final UlidGenerator ids;

    @Inject
    public InstanceService(InstanceStore store, WorkflowEngine engine) {
        this(store, engine, Clock.systemUTC(), UlidGenerator.shared());
    }

    InstanceService(InstanceStore store, WorkflowEngine engine, Clock clock, UlidGenerator ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.engine = Objects.requireNonNull(engine, "engine");
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
        Objects.requireNonNull(request, "request");
        if (!engine.hasDefinition(request.definition())) {
            throw new DefinitionNotFoundException(request.definition());
        }
        InstanceRecord candidate = InstanceRecord.starting(ids.next(), request, now());
        InstanceStore.Insertion insertion = store.insertIfAbsent(candidate, REASON_REQUESTED);
        InstanceRecord row = insertion.record();
        if (!insertion.created()) {
            if (!row.requestFingerprint().equals(request.fingerprint())) {
                throw new IdempotencyKeyReusedException(row.id());
            }
            return new StartResult(row, false);
        }
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
        InstanceRecord prepared = store.replace(
                row.successor(now(), InstanceStatus.STARTING, launch.engineInstanceId(), row.startAttempts(),
                        row.lastError(), row.waitDeadline()),
                row.version(), REASON_PREPARED, RUNTIME_ACTOR);
        try {
            launch.start();
        } catch (RuntimeException e) {
            return new StartResult(recordStartFailure(prepared, e), true);
        }
        InstanceRecord running = store.replace(
                prepared.successor(now(), InstanceStatus.RUNNING, prepared.engineInstanceId(),
                        prepared.startAttempts(), null, prepared.waitDeadline()),
                prepared.version(), REASON_ACCEPTED, RUNTIME_ACTOR);
        return new StartResult(running, true);
    }

    private InstanceRecord recordStartFailure(InstanceRecord row, RuntimeException cause) {
        String error = cause.getClass().getSimpleName();
        if (error.isEmpty()) {
            error = cause.getClass().getName();
        }
        LOG.warnf(cause, "engine start attempt %d failed for instance %s", row.startAttempts() + 1, row.id());
        InstanceRecord next = row.successor(now(), InstanceStatus.STARTING, row.engineInstanceId(),
                row.startAttempts() + 1, truncate(error), row.waitDeadline());
        return store.replace(next, row.version(), REASON_START_FAILED, RUNTIME_ACTOR);
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
