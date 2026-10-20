package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Data returned AFTER an idempotent consumer has committed/read back the exact event/source binding. This value does
 * not itself append, verify a hash chain, grant authority or permit dispatch. Unknown commit replies require the same
 * retained source/id/content readback; a thrown call does not prove absence. No consumer implementation supplied here.
 */
public record AuditReceipt(AuditSource source, Ulid eventId, String chainId, long chainSequence, String hash,
                           String contentFingerprint) {
    private static final Pattern SHA256=Pattern.compile("[0-9a-f]{64}");
    public AuditReceipt {
        Objects.requireNonNull(source,"source"); Objects.requireNonNull(eventId,"eventId");
        chainId=Checks.requireMatching("audit chain",chainId,AuditDraftFingerprint.CHAIN);
        Checks.requireInRange("chain sequence",chainSequence,1,Long.MAX_VALUE);
        hash=Checks.requireMatching("sealed audit hash",hash,SHA256);
        contentFingerprint=Checks.requireMatching("audit draft fingerprint",contentFingerprint,SHA256);
        if(!contentFingerprint.equals(source.contentFingerprint())) throw new IllegalArgumentException("audit source content differs");
    }

    /** Checks retained data equality, not storage/chain authenticity or current caller/dispatch permission. */
    public void requireMatches(AuditSource expected, AuditEvent retainedDraft) {
        Objects.requireNonNull(expected,"expected"); Objects.requireNonNull(retainedDraft,"retainedDraft");
        if(!source.equals(expected) || !eventId.equals(retainedDraft.id())
                || !chainId.equals(AuditDraftFingerprint.chainOf(retainedDraft))
                || !contentFingerprint.equals(AuditDraftFingerprint.of(retainedDraft)))
            throw new IllegalStateException("audit receipt differs from retained source and draft");
    }
}
