package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The front door for components that record decisions with payloads. It applies the retention rule of the event type
 * (M15), stores a reference payload when the rule asks for one, and appends the event to its chain through
 * {@link AuditTrail}. Callers that only have attributes can use the trail (or its {@link AuditSink}) directly.
 *
 * <p>Order of writes for STORE_REFERENCE_ONLY: the payload is stored first, then the event. A failure in between leaves
 * an unreferenced payload, which is harmless and can be removed; the reverse order would leave an event whose
 * reference points nowhere.
 */
public final class AuditRecorder {

    /** Whether the payload of an event can be found and matches its digest. */
    public enum PayloadStatus {
        /** The event keeps its payload inline or redacted; there is nothing external to check. */
        NOT_REFERENCED,
        /** Stored and matching the digest in the event. */
        MATCHES,
        /** Deleted by retention (or never stored). The event and the chain are unaffected. */
        DELETED,
        /** Stored, but its content no longer matches the digest in the event. */
        DOES_NOT_MATCH
    }

    private final AuditTrail trail;
    private final PayloadStore payloads;
    private final RetentionPolicy retention;
    private final Clock clock;

    public AuditRecorder(AuditTrail trail, PayloadStore payloads, RetentionPolicy retention, Clock clock) {
        this.trail = Objects.requireNonNull(trail, "trail");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records the request and returns the stored event. */
    public AuditEvent record(AuditRequest request) {
        Objects.requireNonNull(request, "request");
        RetentionPolicy.Rule rule = retention.ruleFor(request.action());
        Map<String, String> attributes = request.envelope();
        attributes.put(AuditKeys.PAYLOAD_FORM, rule.form().name());
        switch (rule.form()) {
            case STORE_INLINE -> putFields(attributes, request.payload(), rule, false);
            case STORE_REDACTED -> putFields(attributes, request.payload(), rule, true);
            case STORE_REFERENCE_ONLY -> {
                String refId = UlidGenerator.shared().next().value();
                payloads.put(refId, request.payload());
                attributes.put(AuditKeys.PAYLOAD_REF, refId);
                attributes.put(AuditKeys.PAYLOAD_DIGEST, PayloadProtection.digest(request.payload()));
            }
            default -> throw new IllegalStateException("unhandled retention form " + rule.form());
        }
        return trail.append(AuditDraft.of(UlidGenerator.shared().next(), clock.instant(), request.actor(),
                request.action(), request.resourceType(), request.resourceId(), attributes));
    }

    /**
     * Checks an event's external payload against its digest. Events without a reference return
     * {@link PayloadStatus#NOT_REFERENCED}.
     */
    public PayloadStatus payloadStatus(AuditEvent event) {
        Optional<String> ref = Optional.ofNullable(event.attributes().get(AuditKeys.PAYLOAD_REF));
        if (ref.isEmpty()) {
            return PayloadStatus.NOT_REFERENCED;
        }
        Optional<Map<String, String>> stored = payloads.get(ref.get());
        if (stored.isEmpty()) {
            return PayloadStatus.DELETED;
        }
        return PayloadProtection.digest(stored.get()).equals(event.attributes().get(AuditKeys.PAYLOAD_DIGEST))
                ? PayloadStatus.MATCHES
                : PayloadStatus.DOES_NOT_MATCH;
    }

    private void putFields(Map<String, String> attributes, Map<String, String> payload, RetentionPolicy.Rule rule,
                           boolean redact) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : payload.entrySet()) {
            String value = field.getValue();
            if (redact && rule.redactedPaths().contains(field.getKey())) {
                value = PayloadProtection.redact(saltOf(), field.getKey(), value);
            }
            fields.put(AuditKeys.FIELD_PREFIX + field.getKey(), value);
        }
        attributes.putAll(fields);
    }

    private String saltOf() {
        return retention.redactionSalt().orElseThrow(() -> new IllegalStateException("no redaction salt"));
    }
}
