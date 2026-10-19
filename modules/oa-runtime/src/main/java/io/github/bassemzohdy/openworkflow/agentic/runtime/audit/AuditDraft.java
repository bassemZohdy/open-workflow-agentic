package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.Map;

/** Builds the unsealed {@link AuditEvent} that callers pass to {@link AuditSink#record}. */
public final class AuditDraft {

    private AuditDraft() {
    }

    /**
     * An event with the given content and placeholder chain fields (sequence 1, genesis hashes). The trail replaces
     * the placeholders when it appends the event.
     */
    public static AuditEvent of(Ulid id, Instant occurredAt, Subject actor, String action, String resourceType,
                                String resourceId, Map<String, String> attributes) {
        return new AuditEvent(id, 1L, occurredAt, actor, action, resourceType, resourceId, attributes,
                AuditHash.GENESIS, AuditHash.GENESIS);
    }
}
