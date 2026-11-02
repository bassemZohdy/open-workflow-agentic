package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import java.net.URI;
import java.util.Objects;

/**
 * Candidate immutable attribution for one event-triggered start. This is data, not authorization, a receipt, or
 * permission to start a workflow. The route, producer, run owner, input and original grant must be supplied by a
 * trusted admission path; none may be inferred from event extensions. No production path currently consumes it.
 */
public final class EventStartBinding {
    private final String tenant, startKey, requestFingerprint, fingerprint;
    private final Subject owner;
    private final Ulid originalGrant;

    /** Stable across redelivery; content and routing changes are detected by the separate binding fingerprint. */
    public static String startKey(String tenant, String source, String eventId) {
        tenant = Checks.requireText("tenant", tenant, 256);
        source = Checks.requireText("event source", source, 1024);
        eventId = Checks.requireText("event id", eventId, 256);
        URI.create(source);
        if (CanonicalEncoder.strictUtf8(tenant).length + CanonicalEncoder.strictUtf8(source).length
                + CanonicalEncoder.strictUtf8(eventId).length > 2000)
            throw new IllegalArgumentException("event identity exceeds the storage key byte limit");
        return "event-" + CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.event.start.key.v1")
                .text(tenant).text(source).text(eventId).toBytes());
    }

    public EventStartBinding(StructuredEvent event, StartRequest request, Subject producer, Ulid originalGrant,
                             String bindingId, String bindingVersion) {
        Objects.requireNonNull(event); Objects.requireNonNull(request); Objects.requireNonNull(producer);
        Objects.requireNonNull(originalGrant, "originalGrant");
        bindingId = Checks.requireText("binding id", bindingId, 256);
        bindingVersion = Checks.requireText("binding version", bindingVersion, 128);
        this.tenant = request.tenant(); this.owner = request.owner(); this.originalGrant = originalGrant;
        this.startKey = startKey(tenant, event.source(), event.id());
        if (!startKey.equals(request.idempotencyKey()) || request.retriedFrom() != null)
            throw new IllegalArgumentException("event start requires its own derived key and no retry parent");
        this.requestFingerprint = request.fingerprint();
        this.fingerprint = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.event.start.binding.v1")
                .text(tenant).text(startKey).text(event.canonicalEnvelope())
                .text(bindingId).text(bindingVersion).text(producer.kind().name()).text(producer.id())
                .text(owner.kind().name()).text(owner.id()).text(originalGrant.value())
                .text(requestFingerprint).toBytes());
    }

    public String tenant() { return tenant; }
    public String startKey() { return startKey; }
    public String requestFingerprint() { return requestFingerprint; }
    public Subject owner() { return owner; }
    public Ulid originalGrant() { return originalGrant; }
    public String fingerprint() { return fingerprint; }

    /** Do not include event content, subjects, or original grant in logs. */
    @Override public String toString() { return "EventStartBinding[redacted]"; }
}
