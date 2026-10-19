package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import java.util.Map;
import java.util.Optional;

/**
 * Where payloads of the STORE_REFERENCE_ONLY form are kept, outside the event log. Unlike the event log, this store
 * may delete: retention can remove a payload, and the chain still verifies because the event keeps only the reference
 * and the digest.
 */
public interface PayloadStore {

    /** Stores {@code payload} under {@code refId}. Overwriting an existing reference is refused. */
    void put(String refId, Map<String, String> payload);

    /** The payload, or empty when it was never stored or has been deleted by retention. */
    Optional<Map<String, String>> get(String refId);

    /** Deletes the payload. Idempotent. The audit chain is not affected. */
    void delete(String refId);
}
