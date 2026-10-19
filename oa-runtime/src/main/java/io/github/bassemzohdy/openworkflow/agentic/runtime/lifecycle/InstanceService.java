package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
    /** The reason of the new instance's first history row when an operator retries a FAILED instance (amendment 3). */
    static final String REASON_RETRIED = "operator.retry";

    /** Prefix of the idempotency key that a retry creates, followed by a SHA-256 hex digest. */
    static final String RETRY_KEY_PREFIX = "retry-";

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
        Objects.requireNonNull(request, "request");
        return startAs(request, REASON_REQUESTED, request.owner());
    }

    /**
     * Retries a FAILED instance by creating a new instance linked to it (ADR 0019, amendment 3, issue #23). The new row
     * keeps the tenant, owner, definition, pinned version and input of the failed one, gets its own ULID, and records
     * the failed instance in {@code retried_from}. The failed row is not changed. Then the new instance is started
     * through {@link #start}.
     *
     * <p>The key of the new instance is derived from the operation's Idempotency-Key ({@link #retryKey}). A repeat of
     * the same call finds the row its first call created, and nothing starts again. At most one retry of a failed
     * instance may be non-terminal: while one is live, a retry with another key is refused.
     *
     * @param failed        the instance, in status FAILED, as read in the caller's tenant
     * @param operator      the subject that asked for the retry; the actor of the new instance's first history row
     * @param operationKey  the Idempotency-Key header of the operation call
     * @throws LiveRetryExistsException      another retry of this instance is not terminal; nothing is created
     * @throws DefinitionNotFoundException   the engine does not hold the pinned definition; nothing is created
     * @throws IdempotencyKeyReusedException the derived key was used for a different request
     */
    public StartResult retryFailed(InstanceRecord failed, Subject operator, String operationKey) {
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operationKey, "operationKey");
        if (failed.status() != InstanceStatus.FAILED) {
            throw new IllegalArgumentException("only a FAILED instance is retried by a new instance");
        }
        String key = retryKey(operationKey, failed.id());
        StartRequest request = new StartRequest(failed.tenant(), failed.owner(), failed.definition(), failed.input(),
                key, failed.id());
        Optional<InstanceRecord> same = store.findByKey(failed.tenant(), key);
        if (same.isPresent()) {
            if (!same.get().requestFingerprint().equals(request.fingerprint())) {
                throw new IdempotencyKeyReusedException(same.get().id());
            }
            return new StartResult(same.get(), false);
        }
        if (store.liveRetryOf(failed.tenant(), failed.id()).isPresent()) {
            throw new LiveRetryExistsException(failed.id());
        }
        return startAs(request, REASON_RETRIED, operator);
    }

    /**
     * The idempotency key of the retry that one operation call creates: {@code retry-} and the SHA-256 hex digest of the
     * failed instance id and the call's key. The digest keeps the key within the key rule (70 characters), and the same
     * call on the same instance always gives the same key.
     */
    static String retryKey(String operationKey, Ulid failedId) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(failedId, "failedId");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("retry|" + failedId.value() + "|" + operationKey).getBytes(StandardCharsets.UTF_8));
            return RETRY_KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }

    private StartResult startAs(StartRequest request, String reason, Subject actor) {
        if (!engine.hasDefinition(request.definition())) {
            throw new DefinitionNotFoundException(request.definition());
        }
        InstanceRecord candidate = InstanceRecord.starting(ids.next(), request, now());
        InstanceStore.Insertion insertion = store.insertIfAbsent(candidate, reason, actor);
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
            // An operator changed the row while the engine was being prepared (WP-105d, a cancel). Do not start the
            // prepared instance; ask the engine to discard it so the cancellation does not leave an unregistered
            // pending instance behind.
            try {
                if (!launch.discard()) {
                    LOG.warnf("prepared engine instance %s for %s could not be discarded after a registry race",
                            launch.engineInstanceId(), row.id());
                }
            } catch (RuntimeException discardFailed) {
                LOG.warnf(discardFailed, "could not discard prepared engine instance %s after a registry race",
                        launch.engineInstanceId());
            }
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
            // An operator may have cancelled the row meanwhile (ADR 0019, amendment 4): report the stored state.
            LOG.debugf("instance %s moved before the start failure was recorded", row.id());
            return store.find(row.tenant(), row.id()).orElse(next);
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
