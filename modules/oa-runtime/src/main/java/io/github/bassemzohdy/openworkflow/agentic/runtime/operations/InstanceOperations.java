package io.github.bassemzohdy.openworkflow.agentic.runtime.operations;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.correlation.CorrelationIdFilter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditKeys;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.VersionStamps;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionNotFoundException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.HistoryEntry;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.IdempotencyKeyReusedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.IllegalTransitionException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.LiveRetryExistsException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Reconciler;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StaleVersionException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartResult;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.SafeRecovery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * The operations API on registry instances (WP-105d, docs/lifecycle.md, section 11): inspect, cancel, retry, pause and
 * recover. Every call goes through this class in the same order:
 *
 * <ol>
 *   <li>Validate the request shape (the Idempotency-Key header for a mutating call). Nothing is audited for a malformed
 *       request, because it names no operation.</li>
 *   <li>Ask the policy enforcement point. A DENY is 403 and an ERROR is 503; both change nothing.</li>
 *   <li>Write {@code operation.attempt} to the audit trail, with the operator, the instance, the operation and the
 *       decision reference. If that write fails, the call is refused with 503 before anything changes.</li>
 *   <li>Load the instance in the caller's tenant (404 otherwise).</li>
 *   <li>For a mutating call, reserve the idempotency key. A repeat of a completed request gets its stored response.</li>
 *   <li>Change the state with {@link InstanceStore#replace}, with the operator as the actor. That write takes the row
 *       lock and checks the version, so a concurrent change makes this call a 409 and never overwrites it, and it
 *       writes the history and the outbox row in one transaction. This always happens before the reconciler is called.</li>
 *   <li>Call the reconciler's entry point for the operation ({@link Reconciler#retry}, {@link Reconciler#recover}).</li>
 *   <li>Write {@code operation.result} with the outcome, and store the response under the key.</li>
 * </ol>
 *
 * <p>Operations touch only the instance named in the path. They never change another instance's rows.
 */
@ApplicationScoped
public class InstanceOperations {

    /** The operations, as the API names them and the audit trail records them. */
    public enum Operation {
        INSPECT, CANCEL, RETRY, PAUSE, RECOVER;

        /** The lower-case name used on the wire, in the audit trail and in the policy resource. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** True for the operations that change state or that a client must be able to repeat safely. */
        public boolean mutating() {
            return this != INSPECT;
        }
    }

    /** The response of an operation: an HTTP status and a JSON object. */
    public record Outcome(int status, Map<String, Object> body) {
    }

    /** Audit action of the attempt, written before anything changes. */
    public static final String ACTION_ATTEMPT = "operation.attempt";
    /** Audit action of the result, written after the operation ran or was refused. */
    public static final String ACTION_RESULT = "operation.result";
    /** Resource type of the operation events. */
    public static final String RESOURCE_TYPE = "instance";

    /** Detail returned for pause, which no instance state can hold yet (docs/lifecycle.md, section 11). */
    static final String PAUSE_DETAIL = "pause is not available: no instance state holds work at a checkpoint yet";

    static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final Logger LOG = Logger.getLogger(InstanceOperations.class);

    private final InstanceStore store;
    private final Reconciler reconciler;
    private final Pep pep;
    private final AuditSink audit;
    private final OperationKeys keys;
    private final InstanceInspection inspection;
    private final InstanceService service;
    private final Clock clock;

    /** The audit trail is injected by its concrete type, because {@code AuditProducers} also produces the sink interface, so injecting by interface would be ambiguous. */
    @Inject
    public InstanceOperations(InstanceStore store, Reconciler reconciler, Pep pep, AuditTrail audit,
                              OperationKeys keys, InstanceInspection inspection, InstanceService service) {
        this(store, reconciler, pep, audit, keys, inspection, service, Clock.systemUTC());
    }

    InstanceOperations(InstanceStore store, Reconciler reconciler, Pep pep, AuditSink audit, OperationKeys keys,
                       InstanceInspection inspection, InstanceService service, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.pep = Objects.requireNonNull(pep, "pep");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs one operation on one instance for a caller.
     *
     * @param idempotencyKey the Idempotency-Key header; required for every mutating operation, ignored for inspect
     * @return the status and body to send
     * @throws ProblemException for every refusal and failure the client must see (400, 403, 404, 409, 501, 503)
     */
    public Outcome perform(Operation operation, CallerContext caller, Ulid instanceId, String idempotencyKey) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(instanceId, "instanceId");
        String key = operation.mutating() ? requireKey(idempotencyKey) : null;

        Subject operator = caller.subject();
        Decision decision = decide(operation, operator, caller.scopes(), instanceId);
        String attemptOutcome = decision.allows() ? "allowed"
                : decision.outcome() == DecisionOutcome.ERROR ? "error" : "denied";
        try {
            writeAudit(ACTION_ATTEMPT, attemptOutcome, operation, operator, caller.tenant(), instanceId, decision);
        } catch (RuntimeException unrecorded) {
            LOG.errorf(unrecorded, "operation %s on %s refused: the attempt could not be audited", operation.wire(),
                    instanceId);
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the audit trail is unavailable; nothing was changed");
        }
        if (!decision.allows()) {
            throw refusal(decision);
        }

        Done done;
        try {
            done = execute(operation, caller, instanceId, key);
        } catch (Refusal refused) {
            writeResult(refused.result, operation, operator, caller.tenant(), instanceId, decision);
            throw new ProblemException(refused.status(), refused.getMessage());
        } catch (StoreUnavailableException unavailable) {
            LOG.errorf(unavailable, "operation %s on %s failed in the registry", operation.wire(), instanceId);
            writeResult("store-unavailable", operation, operator, caller.tenant(), instanceId, decision);
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the registry is unavailable; the operation may be repeated with the same Idempotency-Key");
        } catch (RuntimeException failed) {
            writeResult("failed", operation, operator, caller.tenant(), instanceId, decision);
            throw failed;
        }
        writeResult(done.result(), operation, operator, caller.tenant(), instanceId, decision);
        return done.outcome();
    }

    // ---- authorization -----------------------------------------------------------------------------------------------

    private Decision decide(Operation operation, Subject operator, Set<String> scopes, Ulid instanceId) {
        return switch (operation) {
            case CANCEL -> pep.operation(SafeRecovery.RecoveryOperation.CANCEL, operator, scopes, instanceId);
            case PAUSE -> pep.operation(SafeRecovery.RecoveryOperation.PAUSE, operator, scopes, instanceId);
            case INSPECT -> pep.instanceOperation(Pep.InstanceOperation.INSPECT, operator, scopes, instanceId);
            case RETRY -> pep.instanceOperation(Pep.InstanceOperation.RETRY, operator, scopes, instanceId);
            case RECOVER -> pep.instanceOperation(Pep.InstanceOperation.RECOVER, operator, scopes, instanceId);
        };
    }

    private static ProblemException refusal(Decision decision) {
        if (decision.outcome() == DecisionOutcome.ERROR) {
            return new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the policy decision is unavailable; nothing was changed");
        }
        return new ProblemException(Response.Status.FORBIDDEN,
                "the operator is not authorized for this operation on this instance");
    }

    // ---- execution -----------------------------------------------------------------------------------------------------

    private Done execute(Operation operation, CallerContext caller, Ulid instanceId, String key) {
        String tenant = caller.tenant();
        Optional<InstanceRecord> found = store.find(tenant, instanceId);
        if (found.isEmpty()) {
            throw new Refusal("not-found", Response.Status.NOT_FOUND.getStatusCode(), "no such instance");
        }
        return switch (operation) {
            case INSPECT -> new Done("inspected", new Outcome(Response.Status.OK.getStatusCode(), inspect(found.get())));
            case PAUSE -> throw new Refusal("not-implemented", Response.Status.NOT_IMPLEMENTED.getStatusCode(),
                    PAUSE_DETAIL);
            case CANCEL, RETRY, RECOVER -> mutate(operation, caller, instanceId, key);
        };
    }

    /** A mutating call: reserve the key, run the operation, store the response. A refusal releases the key. */
    private Done mutate(Operation operation, CallerContext caller, Ulid instanceId, String key) {
        String tenant = caller.tenant();
        OperationKeys.Reservation reservation = keys.reserve(tenant, key, operation.name(), instanceId,
                caller.subject(), OperationKeys.fingerprint(operation.wire(), instanceId));
        switch (reservation.state()) {
            case REPLAY -> {
                return new Done("replayed", new Outcome(reservation.status(), reservation.body()));
            }
            case KEY_REUSED -> throw new Refusal("key-reused", Response.Status.CONFLICT.getStatusCode(),
                    "the Idempotency-Key was used for a different request");
            case IN_PROGRESS -> throw new Refusal("in-progress", Response.Status.CONFLICT.getStatusCode(),
                    "an operation with this Idempotency-Key is still running or did not finish");
            case RESERVED -> {
                // Run below.
            }
            default -> throw new IllegalStateException("unhandled reservation " + reservation.state());
        }
        try {
            // Read again under the reservation: the state the decision is taken on is the one now stored.
            InstanceRecord row = store.find(tenant, instanceId).orElseThrow(() ->
                    new Refusal("not-found", Response.Status.NOT_FOUND.getStatusCode(), "no such instance"));
            Done done = switch (operation) {
                case CANCEL -> cancel(row, caller.subject());
                case RETRY -> retry(row, caller.subject(), key);
                case RECOVER -> recover(row, caller.subject());
                default -> throw new IllegalStateException("not a mutating operation: " + operation);
            };
            keys.complete(tenant, key, done.outcome().status(), done.outcome().body());
            return done;
        } catch (RuntimeException failed) {
            releaseQuietly(tenant, key);
            throw failed;
        }
    }

    /**
     * Cancel (ADR 0019): RUNNING, WAITING and STARTING move to CANCEL_REQUESTED. A STARTING row with no engine instance
     * id goes straight to CANCELLED (ADR 0019, amendment 4, issue #24): the engine never started that instance, so there
     * is nothing for the engine's report to confirm. The version read here decides that case. A start that prepares
     * the engine instance at the same moment writes the row first, so this write is refused as stale (409) and the
     * cancel is decided again. For every other row the engine's own report completes the cancel, so the reconciler sends
     * the engine cancel now, or leaves it to its next pass when the engine is not reached. A terminal instance is a no-op
     * with an explicit result.
     */
    private Done cancel(InstanceRecord row, Subject operator) {
        if (row.status().isTerminal()) {
            return new Done("noop-terminal", new Outcome(Response.Status.OK.getStatusCode(),
                    body(Operation.CANCEL, "already-terminal", row, null)));
        }
        if (row.status() == InstanceStatus.CANCEL_REQUESTED) {
            return new Done("already-requested", new Outcome(Response.Status.OK.getStatusCode(),
                    body(Operation.CANCEL, "already-requested", row, null)));
        }
        if (row.status() == InstanceStatus.STARTING && row.engineInstanceId() == null) {
            InstanceRecord cancelled = write(row.successor(now(), InstanceStatus.CANCELLED, null, row.startAttempts(),
                    row.lastError(), null), row.version(), "operator.cancel", operator);
            return new Done("cancelled", new Outcome(Response.Status.OK.getStatusCode(),
                    body(Operation.CANCEL, "cancelled", cancelled, null)));
        }
        InstanceRecord requested = write(row.successor(now(), InstanceStatus.CANCEL_REQUESTED, row.engineInstanceId(),
                row.startAttempts(), row.lastError(), row.waitDeadline()), row.version(), "operator.cancel", operator);
        String note = null;
        try {
            Reconciler.ReconcileReport report = reconciler.recover(requested.tenant(), requested.id().value());
            note = report.count("busy") > 0 ? "a repair holds the instance; the reconciler re-sends the cancel"
                    : null;
        } catch (RuntimeException notReached) {
            LOG.warnf(notReached, "cancel of %s recorded; the engine was not reached", requested.id());
            note = "the engine was not reached; the reconciler re-sends the cancel";
        }
        InstanceRecord after = reload(requested);
        return new Done("cancel-requested", new Outcome(Response.Status.ACCEPTED.getStatusCode(),
                body(Operation.CANCEL, "cancel-requested", after, note)));
    }

    /**
     * Retry. A FAILED instance gets a new linked instance ({@link #retryFailed}, ADR 0019, amendment 3). A STARTING row
     * is started again now, bounded by the reconciler's attempt limit. Any other status is refused with 409.
     */
    private Done retry(InstanceRecord row, Subject operator, String key) {
        if (row.status() == InstanceStatus.FAILED) {
            return retryFailed(row, operator, key);
        }
        if (row.status() != InstanceStatus.STARTING) {
            throw new Refusal("refused-status", Response.Status.CONFLICT.getStatusCode(),
                    "retry applies to STARTING rows and to FAILED rows; this instance is " + row.status());
        }
        InstanceRecord requested = write(row.successor(now(), InstanceStatus.STARTING, row.engineInstanceId(),
                row.startAttempts(), row.lastError(), row.waitDeadline()), row.version(), "operator.retry", operator);
        Reconciler.ReconcileReport report = reconciler.retry(requested.tenant(), requested.id().value());
        if (report.count("busy") > 0) {
            throw new Refusal("busy", Response.Status.CONFLICT.getStatusCode(),
                    "a repair holds the instance; repeat the request with the same Idempotency-Key");
        }
        InstanceRecord after = reload(requested);
        if (after.version() == requested.version()) {
            throw new Refusal("nothing-retried", Response.Status.CONFLICT.getStatusCode(),
                    "the retry is in the history but the reconciler had nothing to start; the instance is "
                            + after.status());
        }
        return new Done("retry-requested", new Outcome(Response.Status.ACCEPTED.getStatusCode(),
                body(Operation.RETRY, "retry-requested", after, null)));
    }

    /**
     * Retry of a FAILED instance (ADR 0019, amendment 3, issue #23). The failed row is not changed. A new instance with
     * the same tenant, owner, definition, pinned version and input is inserted, linked by {@code retriedFrom}, and
     * started through the normal start path. A repeat of the same call returns the instance its first call created
     * (200, outcome {@code retry-existing}) and starts nothing. At most one retry of a failed instance is non-terminal:
     * another retry while one is live is refused with 409.
     */
    private Done retryFailed(InstanceRecord failed, Subject operator, String key) {
        StartResult result;
        try {
            result = service.retryFailed(failed, operator, key);
        } catch (LiveRetryExistsException live) {
            throw new Refusal("refused-live-retry", Response.Status.CONFLICT.getStatusCode(),
                    "this FAILED instance already has a retry that is not terminal; a new retry is allowed once it is");
        } catch (DefinitionNotFoundException missing) {
            throw new Refusal("refused-definition", 422,
                    "the engine does not hold the pinned definition of this instance; no retry was created");
        } catch (IdempotencyKeyReusedException reused) {
            throw new Refusal("key-reused", Response.Status.CONFLICT.getStatusCode(),
                    "the Idempotency-Key was used for a different request");
        }
        String outcome = result.created() ? "retry-created" : "retry-existing";
        Map<String, Object> body = body(Operation.RETRY, outcome, result.record(), null);
        body.put("retriedFrom", failed.id().value());
        int status = result.created() ? Response.Status.ACCEPTED.getStatusCode() : Response.Status.OK.getStatusCode();
        return new Done(outcome, new Outcome(status, body));
    }

    /**
     * Recover: repairs the instance the way a reconciler pass would, without the staleness checks. Refused for a
     * terminal instance, which the reconciler never changes.
     */
    private Done recover(InstanceRecord row, Subject operator) {
        if (row.status().isTerminal()) {
            throw new Refusal("refused-terminal", Response.Status.CONFLICT.getStatusCode(),
                    "nothing to recover: the instance is " + row.status() + " (terminal)");
        }
        InstanceRecord requested = write(row.successor(now(), row.status(), row.engineInstanceId(),
                row.startAttempts(), row.lastError(), row.waitDeadline()), row.version(), "operator.recover", operator);
        Reconciler.ReconcileReport report = reconciler.recover(requested.tenant(), requested.id().value());
        if (report.count("busy") > 0) {
            throw new Refusal("busy", Response.Status.CONFLICT.getStatusCode(),
                    "a repair holds the instance; repeat the request with the same Idempotency-Key");
        }
        InstanceRecord after = reload(requested);
        Map<String, Object> body = body(Operation.RECOVER, "recover-requested", after, null);
        body.put("reconciler", new LinkedHashMap<>(report.counts()));
        return new Done("recover-requested", new Outcome(Response.Status.OK.getStatusCode(), body));
    }

    /** One state change with the operator as actor. A version that moved since the read is a 409. */
    private InstanceRecord write(InstanceRecord next, long expectedVersion, String reason, Subject operator) {
        try {
            return store.replace(next, expectedVersion, reason, operator);
        } catch (StaleVersionException stale) {
            throw new Refusal("stale", Response.Status.CONFLICT.getStatusCode(),
                    "the instance changed while the operation was decided; read it and decide again");
        } catch (IllegalTransitionException illegal) {
            throw new Refusal("illegal-transition", Response.Status.CONFLICT.getStatusCode(), illegal.getMessage());
        }
    }

    private InstanceRecord reload(InstanceRecord row) {
        return store.find(row.tenant(), row.id()).orElse(row);
    }

    private Map<String, Object> inspect(InstanceRecord row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", instance(row));
        List<String> retries = new ArrayList<>();
        for (Ulid retry : store.retriesOf(row.tenant(), row.id())) {
            retries.add(retry.value());
        }
        out.put("retries", retries);
        List<Map<String, Object>> history = new ArrayList<>();
        for (HistoryEntry entry : store.history(row.id())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", entry.version());
            item.put("from", entry.fromStatus() == null ? null : entry.fromStatus().name());
            item.put("to", entry.toStatus().name());
            item.put("reason", entry.reason());
            item.put("actor", actor(entry.actor()));
            item.put("occurredAt", InstanceInspection.text(entry.occurredAt()));
            history.add(item);
        }
        out.put("history", history);
        out.put("taskView", inspection.taskView(row.tenant(), row.id().value()));
        out.put("outbox", inspection.outbox(row.id().value()));
        out.put("repairs", inspection.repairs(row.id().value()));
        out.put("authority", inspection.authority(row.id().value()));
        return out;
    }

    private static Map<String, Object> body(Operation operation, String outcome, InstanceRecord row, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation.wire());
        body.put("outcome", outcome);
        body.put("instance", instance(row));
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private static Map<String, Object> instance(InstanceRecord row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.id().value());
        out.put("definitionId", row.definition().definitionId());
        out.put("definitionVersion", row.definition().version());
        out.put("status", row.status().name());
        out.put("retriedFrom", row.retriedFrom() == null ? null : row.retriedFrom().value());
        out.put("engineInstanceId", row.engineInstanceId());
        out.put("startAttempts", row.startAttempts());
        out.put("lastError", row.lastError());
        out.put("waitDeadline", InstanceInspection.text(row.waitDeadline()));
        out.put("version", row.version());
        out.put("createdAt", InstanceInspection.text(row.createdAt()));
        out.put("updatedAt", InstanceInspection.text(row.updatedAt()));
        return out;
    }

    private static Map<String, Object> actor(Subject subject) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", subject.kind().name());
        out.put("id", subject.id());
        return out;
    }

    // ---- audit -----------------------------------------------------------------------------------------------------

    private void writeResult(String result, Operation operation, Subject operator, String tenant, Ulid instanceId,
                             Decision decision) {
        try {
            writeAudit(ACTION_RESULT, result, operation, operator, tenant, instanceId, decision);
        } catch (RuntimeException unrecorded) {
            // The attempt event is the durable record of intent and was written before the change. A failed result
            // event is logged and the response still reports what happened.
            LOG.errorf(unrecorded, "result %s of %s on %s was not audited", result, operation.wire(), instanceId);
        }
    }

    private void writeAudit(String action, String outcome, Operation operation, Subject operator, String tenant,
                            Ulid instanceId, Decision decision) {
        Map<String, String> attributes = new LinkedHashMap<>(VersionStamps.ofDecision(decision).toAttributes());
        attributes.put(AuditKeys.TENANT, tenant);
        attributes.put(AuditKeys.INSTANCE, instanceId.value());
        attributes.put("oa.operation", operation.wire());
        attributes.put("oa.outcome", outcome);
        attributes.put("oa.decided-by", decision.decidedBy());
        Object correlation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlation instanceof String text && !text.isBlank()) {
            attributes.put(AuditKeys.CORRELATION, text);
        }
        audit.record(AuditDraft.of(UlidGenerator.shared().next(), now(), operator, action, RESOURCE_TYPE,
                instanceId.value(), attributes));
    }

    private void releaseQuietly(String tenant, String key) {
        try {
            keys.release(tenant, key);
        } catch (RuntimeException failed) {
            LOG.errorf(failed, "idempotency key %s could not be released; it stays reserved until removed", key);
        }
    }

    // ---- request shape ---------------------------------------------------------------------------------------------

    static String requireKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ProblemException(Response.Status.BAD_REQUEST,
                    "the Idempotency-Key header is required for this operation");
        }
        if (!KEY.matcher(raw).matches()) {
            throw new ProblemException(Response.Status.BAD_REQUEST,
                    "the Idempotency-Key header is not valid (1 to 128 letters, digits and ._:-)");
        }
        return raw;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** The result of an execution that succeeded: its audit label and its response. */
    private record Done(String result, Outcome outcome) {
    }

    /** A refusal inside the execution: its audit label, its status and the detail for the client. */
    private static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String result;
        private final int status;

        Refusal(String result, int status, String detail) {
            super(detail);
            this.result = result;
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
