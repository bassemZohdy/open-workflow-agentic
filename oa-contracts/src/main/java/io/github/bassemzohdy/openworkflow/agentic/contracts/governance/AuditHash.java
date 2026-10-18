package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Computes and verifies the SHA-256 hash chain of audit events (M15, plan section 2).
 *
 * <p>The hash of an event covers a canonical encoding of every field except {@code hash} itself: sequence, id, time,
 * actor, action, resource, sorted attributes and {@code previousHash}. The encoding is length-prefixed
 * ({@link CanonicalEncoder}), so no two different events have the same input to the hash.
 *
 * <p>What the chain detects: a changed field, a changed or recomputed hash that does not match the content, a removed,
 * inserted or reordered event (the sequence and the links break), and a chain that ends at a different head than the
 * one expected. What it cannot detect on its own: someone who rewrites the whole chain from the first changed event
 * and also replaces the head. The head must therefore be kept outside the log (an external anchor), and
 * {@link #verifyChain(List, String)} is the check that uses it.
 */
public final class AuditHash {

    /** The {@code previousHash} of the first event: 64 zero characters. */
    public static final String GENESIS = "0".repeat(64);

    private static final String DOMAIN = "oa.audit.v1";

    private AuditHash() {
    }

    /** The hash that {@code event} should carry: a SHA-256 over its canonical encoding, ignoring its own hash field. */
    public static String compute(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        CanonicalEncoder encoder = new CanonicalEncoder(DOMAIN)
                .number(event.sequence())
                .text(event.id().value())
                .number(event.occurredAt().getEpochSecond())
                .number(event.occurredAt().getNano())
                .text(event.actor().kind().name())
                .text(event.actor().id())
                .text(event.action())
                .text(event.resourceType())
                .text(event.resourceId())
                .number(event.attributes().size());
        for (Map.Entry<String, String> entry : new TreeMap<>(event.attributes()).entrySet()) {
            encoder.text(entry.getKey()).text(entry.getValue());
        }
        encoder.text(event.previousHash());
        return CanonicalEncoder.sha256Hex(encoder.toBytes());
    }

    /** A copy of {@code event} whose hash field is the correct hash of its content. */
    public static AuditEvent seal(AuditEvent event) {
        return new AuditEvent(event.id(), event.sequence(), event.occurredAt(), event.actor(), event.action(),
                event.resourceType(), event.resourceId(), event.attributes(), event.previousHash(), compute(event));
    }

    /**
     * Verifies that the events form an intact chain from {@link #GENESIS}: sequences run 1, 2, 3, each event links to
     * the hash of the one before, and each hash matches its content. An empty list is intact.
     */
    public static ChainVerification verifyChain(List<AuditEvent> events) {
        return verify(events, null);
    }

    /**
     * As {@link #verifyChain(List)}, and the last hash must equal {@code expectedHead}. Use it with a head kept outside
     * the log; the expected head for an empty log is {@link #GENESIS}.
     */
    public static ChainVerification verifyChain(List<AuditEvent> events, String expectedHead) {
        Objects.requireNonNull(expectedHead, "expectedHead");
        return verify(events, expectedHead);
    }

    private static ChainVerification verify(List<AuditEvent> events, String expectedHead) {
        Objects.requireNonNull(events, "events");
        String expectedPrevious = GENESIS;
        for (int i = 0; i < events.size(); i++) {
            AuditEvent event = Objects.requireNonNull(events.get(i), "events must not contain null");
            if (!event.previousHash().equals(expectedPrevious)) {
                return ChainVerification.broken(i, "previous hash does not link to the preceding event");
            }
            if (event.sequence() != i + 1L) {
                return ChainVerification.broken(i, "sequence is not consecutive: expected " + (i + 1)
                        + ", was " + event.sequence());
            }
            if (!compute(event).equals(event.hash())) {
                return ChainVerification.broken(i, "hash does not match the event content");
            }
            expectedPrevious = event.hash();
        }
        if (expectedHead != null && !expectedPrevious.equals(expectedHead)) {
            return ChainVerification.broken(events.size(), "chain does not end at the expected head");
        }
        return ChainVerification.intact();
    }
}
