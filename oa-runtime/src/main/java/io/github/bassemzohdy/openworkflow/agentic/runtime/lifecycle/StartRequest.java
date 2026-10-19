package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A request to start one instance (ADR 0019, Start). The idempotency key is unique per tenant: a repeated request
 * with the same key returns the existing instance and starts nothing.
 *
 * @param tenant         the tenant the instance belongs to
 * @param owner          the subject that asked for the start
 * @param definition     the pinned definition and version
 * @param input          the workflow input; copied, and bounded to {@link #MAX_INPUT_BYTES} as canonical JSON
 * @param idempotencyKey the caller's key for this request
 */
public record StartRequest(String tenant, Subject owner, DefinitionRef definition, Map<String, Object> input,
                           String idempotencyKey) {

    /** Largest accepted input, measured as canonical JSON in UTF-8 bytes. */
    public static final int MAX_INPUT_BYTES = 64 * 1024;
    static final int MAX_TENANT_LENGTH = 256;
    static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public StartRequest {
        tenant = Checks.requireText("tenant", tenant, MAX_TENANT_LENGTH);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(input, "input");
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        idempotencyKey = Checks.requireMatching("idempotencyKey", idempotencyKey, KEY);
        if (Json.canonical(input).getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("input is larger than " + MAX_INPUT_BYTES + " bytes");
        }
    }

    /** The canonical JSON text of the input, as stored. */
    public String canonicalInput() {
        return Json.canonical(input);
    }

    /** The fingerprint that tells a repeated request from a reuse of the key for a different request. */
    public String fingerprint() {
        return Json.fingerprint(definition, canonicalInput());
    }
}
