package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of the append-only audit log (M15). Each event carries the hash of the event before it and its own hash,
 * so the log is a chain: changing, reordering, inserting or removing an event breaks the chain (see {@link AuditHash}).
 *
 * <p>The record only checks the shape of the hash fields. Whether the hashes are correct is decided by
 * {@link AuditHash#verifyChain(java.util.List)}, so a tampered event can be represented and then detected.
 *
 * @param id           the event id (a ULID, sortable by time)
 * @param sequence     position in the chain, starting at 1
 * @param occurredAt   when the event happened
 * @param actor        who or what acted
 * @param action       what happened, for example {@code tool.call.allowed}
 * @param resourceType the kind of resource affected
 * @param resourceId   the resource affected
 * @param attributes   non-sensitive details; at most {@link #MAX_ATTRIBUTES} entries. Payloads are referenced, not
 *                     copied (M15)
 * @param previousHash hash of the event at {@code sequence - 1}, or {@link AuditHash#GENESIS} for the first event
 * @param hash         hash of this event, computed by {@link AuditHash}
 */
public record AuditEvent(
        Ulid id,
        long sequence,
        Instant occurredAt,
        Subject actor,
        String action,
        String resourceType,
        String resourceId,
        Map<String, String> attributes,
        String previousHash,
        String hash) {

    public static final int MAX_ATTRIBUTES = 32;
    public static final int MAX_TEXT_LENGTH = 512;
    static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Checks.requireInRange("sequence", sequence, 1L, Long.MAX_VALUE);
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        action = Checks.requireText("action", action, MAX_TEXT_LENGTH);
        resourceType = Checks.requireText("resourceType", resourceType, MAX_TEXT_LENGTH);
        resourceId = Checks.requireText("resourceId", resourceId, MAX_TEXT_LENGTH);
        attributes = Checks.copyMap("attributes", attributes, MAX_ATTRIBUTES, MAX_TEXT_LENGTH);
        Checks.requireMatching("previousHash", previousHash, SHA256_HEX);
        Checks.requireMatching("hash", hash, SHA256_HEX);
    }
}
