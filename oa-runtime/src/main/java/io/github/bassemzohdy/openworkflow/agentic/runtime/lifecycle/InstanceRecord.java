package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One row of the instance registry (the {@code task.instance} table, ADR 0019). The registry, not the engine, is the
 * record of lifecycle status, definition version and idempotency key.
 *
 * <p>A row is changed only through {@link #successor}, which bumps the version by one. The store writes the change
 * only when the stored version is the one the change was based on.
 *
 * @param id                 the instance id, a ULID; also the engine's instance id is kept separately
 * @param tenant             the tenant that owns the instance
 * @param owner              the subject that asked for the start
 * @param idempotencyKey     the request key, unique per tenant
 * @param requestFingerprint the fingerprint of the request that created the row
 * @param definition         the pinned definition and version
 * @param input              the workflow input
 * @param status             the lifecycle status
 * @param engineInstanceId   the engine's id of the instance, or null until it is prepared
 * @param startAttempts      failed engine start attempts so far
 * @param lastError          the class name of the last start error, or null; never the message
 * @param waitDeadline       the absolute deadline of the current wait, or null (ADR 0019, Deadlines)
 * @param version            optimistic-lock version; 0 when inserted
 * @param createdAt          when the row was created
 * @param updatedAt          when the row last changed
 */
public record InstanceRecord(
        Ulid id,
        String tenant,
        Subject owner,
        String idempotencyKey,
        String requestFingerprint,
        DefinitionRef definition,
        Map<String, Object> input,
        InstanceStatus status,
        String engineInstanceId,
        int startAttempts,
        String lastError,
        Instant waitDeadline,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static final int MAX_ERROR_LENGTH = 256;
    static final int MAX_ENGINE_ID_LENGTH = 64;
    static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public InstanceRecord {
        Objects.requireNonNull(id, "id");
        tenant = Checks.requireText("tenant", tenant, StartRequest.MAX_TENANT_LENGTH);
        Objects.requireNonNull(owner, "owner");
        idempotencyKey = Checks.requireMatching("idempotencyKey", idempotencyKey, StartRequest.KEY);
        requestFingerprint = Checks.requireMatching("requestFingerprint", requestFingerprint, HEX64);
        Objects.requireNonNull(definition, "definition");
        input = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(input, "input")));
        Objects.requireNonNull(status, "status");
        if (engineInstanceId != null) {
            engineInstanceId = Checks.requireText("engineInstanceId", engineInstanceId, MAX_ENGINE_ID_LENGTH);
        }
        Checks.requireInRange("startAttempts", startAttempts, 0, Integer.MAX_VALUE);
        if (lastError != null) {
            lastError = Checks.requireText("lastError", lastError, MAX_ERROR_LENGTH);
        }
        Checks.requireInRange("version", version, 0L, Long.MAX_VALUE);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** The row for a new request: status STARTING, version 0, no engine instance yet. */
    public static InstanceRecord starting(Ulid id, StartRequest request, Instant now) {
        return new InstanceRecord(id, request.tenant(), request.owner(), request.idempotencyKey(),
                request.fingerprint(), request.definition(), request.input(), InstanceStatus.STARTING, null, 0, null,
                null, 0L, now, now);
    }

    /**
     * The next version of this row. Identity and input never change; status, engine id, attempts, error and deadline
     * may. The caller passes the new values; a change that is not a status transition keeps the same status.
     */
    public InstanceRecord successor(Instant at, InstanceStatus nextStatus, String nextEngineInstanceId,
                                    int nextStartAttempts, String nextLastError, Instant nextWaitDeadline) {
        return new InstanceRecord(id, tenant, owner, idempotencyKey, requestFingerprint, definition, input,
                nextStatus, nextEngineInstanceId, nextStartAttempts, nextLastError, nextWaitDeadline, version + 1,
                createdAt, at);
    }

    /** The canonical text of the input, as stored. */
    public String canonicalInput() {
        return Json.canonical(input);
    }
}
