package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.net.URI;
import java.util.Objects;

/**
 * A normalized event for one tenant, instance and logical wait occurrence. Tenant/target routing comes from an
 * authorized ingress, never from trusting CloudEvents extensions. Payload is immutable JSON text; ingress must
 * normalize it before retries. Reusing source/id with different content or routing is refused.
 */
public record InboxMessage(String tenant, String source, String eventId, String type, Ulid instanceId,
                           String correlationKey, String dataJson) {
    public InboxMessage {
        tenant = Checks.requireText("tenant", tenant, 256);
        source = Checks.requireText("source", source, 1024);
        URI.create(source); // CloudEvents source is a URI-reference, not necessarily an absolute URL.
        eventId = Checks.requireText("eventId", eventId, 256);
        if (CanonicalEncoder.strictUtf8(tenant).length + CanonicalEncoder.strictUtf8(source).length
                + CanonicalEncoder.strictUtf8(eventId).length > 2000) {
            throw new IllegalArgumentException("event identity exceeds the storage key byte limit");
        }
        type = Checks.requireText("type", type, 256);
        Objects.requireNonNull(instanceId, "instanceId");
        correlationKey = Checks.requireText("correlationKey", correlationKey, 256);
        Objects.requireNonNull(dataJson, "dataJson");
        if (CanonicalEncoder.strictUtf8(dataJson).length > 64 * 1024) {
            throw new IllegalArgumentException("event data exceeds 64 KiB");
        }
    }

    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.message.v1")
                .text(tenant).text(source).text(eventId).text(type).text(instanceId.value())
                .text(correlationKey).text(dataJson).toBytes());
    }
}
