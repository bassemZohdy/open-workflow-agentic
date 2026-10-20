package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * v1 immutable draft content shared by producer and idempotent audit consumer. Separate from AuditHash's sealed-chain
 * format, which is unchanged. No database, serialization library, signing or authority is supplied by this helper.
 */
public final class AuditDraftFingerprint {
    static final Pattern CHAIN=Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private AuditDraftFingerprint() { }

    /** Explicit chain or default, with the existing runtime chain syntax. No silent invalid-chain fallback. */
    public static String chainOf(AuditEvent draft) {
        Objects.requireNonNull(draft,"draft");
        return Checks.requireMatching("audit chain",draft.attributes().getOrDefault("oa.chain","default"),CHAIN);
    }

    /**
     * SHA256 of length-prefixed strict UTF-8 / signed 8-byte big-endian numbers, in this EXACT order: domain
     * oa.audit.draft.v1; event ULID; occurredAt epochSecond; microsecond-of-second; actor KIND/id; action;
     * resourceType/id; selected chain; attribute count; every key/value sorted by Java String natural order.
     * Central-assigned sequence, previousHash and hash are excluded. Attributes, including explicit oa.chain, are ALL
     * included: absent versus explicit default is different content. Never put this content digest inside its own
     * draft attributes. A finer timestamp is refused instead of rounded into another draft's identity.
     */
    public static String of(AuditEvent draft) {
        Objects.requireNonNull(draft,"draft");
        if(draft.occurredAt().getNano()%1000!=0) throw new IllegalArgumentException("audit draft time must be microsecond-exact");
        var encoder=new CanonicalEncoder("oa.audit.draft.v1").text(draft.id().value())
                .number(draft.occurredAt().getEpochSecond()).number(draft.occurredAt().getNano()/1000)
                .text(draft.actor().kind().name()).text(draft.actor().id()).text(draft.action())
                .text(draft.resourceType()).text(draft.resourceId()).text(chainOf(draft)).number(draft.attributes().size());
        new TreeMap<>(draft.attributes()).forEach((key,value)->encoder.text(key).text(value));
        return CanonicalEncoder.sha256Hex(encoder.toBytes());
    }
}
