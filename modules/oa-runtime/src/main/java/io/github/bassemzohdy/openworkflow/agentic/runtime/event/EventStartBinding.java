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
    private final String tenant, startKey, requestFingerprint, fingerprint, bindingId, bindingVersion;
    private final Subject producer;
    private final StructuredEvent event;
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
        this(event, Objects.requireNonNull(request).tenant(), request.idempotencyKey(), request.fingerprint(),
                request.owner(), producer, originalGrant, bindingId, bindingVersion);
        if (request.retriedFrom() != null)
            throw new IllegalArgumentException("event start cannot be a retry of another instance");
    }

    /** Reconstructs retained data for integrity checking; it does not re-establish current authority. */
    static EventStartBinding restore(StructuredEvent event, String tenant, String key, String requestFingerprint,
                                     Subject owner, Subject producer, Ulid originalGrant, String bindingId,
                                     String bindingVersion) {
        return new EventStartBinding(event, tenant, key, requestFingerprint, owner, producer, originalGrant,
                bindingId, bindingVersion);
    }

    private EventStartBinding(StructuredEvent event, String tenant, String key, String requestFingerprint,
                              Subject owner, Subject producer, Ulid originalGrant, String bindingId,
                              String bindingVersion) {
        this.event = Objects.requireNonNull(event); this.owner = Objects.requireNonNull(owner);
        this.producer = Objects.requireNonNull(producer); this.originalGrant = Objects.requireNonNull(originalGrant);
        this.bindingId = Checks.requireText("binding id", bindingId, 256);
        this.bindingVersion = Checks.requireText("binding version", bindingVersion, 128);
        this.tenant = Checks.requireText("tenant", tenant, 256);
        this.startKey = startKey(tenant, event.source(), event.id());
        if (!startKey.equals(key)) throw new IllegalArgumentException("event start key does not match event identity");
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid start request fingerprint");
        this.requestFingerprint = requestFingerprint;
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
    public Subject producer() { return producer; }
    public String bindingId() { return bindingId; }
    public String bindingVersion() { return bindingVersion; }
    public StructuredEvent event() { return event; }
    public String fingerprint() { return fingerprint; }

    /** Do not include event content, subjects, or original grant in logs. */
    @Override public String toString() { return "EventStartBinding[redacted]"; }
}
