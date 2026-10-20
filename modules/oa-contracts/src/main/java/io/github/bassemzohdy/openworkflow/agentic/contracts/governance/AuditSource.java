package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable identity/content of one retained effect-journal projection. Data, never permission or an audit append.
 * Sequence is the committed source journal id (positive, possibly gapped), not central chain sequence or global order.
 * The producer retains the original source, draft id/time and content before delivery; retry never creates another id.
 */
public record AuditSource(int schemaVersion, String scopeFingerprint, String keyFingerprint, Transition transition,
                          long sequence, String contentFingerprint) {
    private static final Pattern SHA256=Pattern.compile("[0-9a-f]{64}");

    /** Finite v1 producer facts. A state transition is not inferred from successful callback return. */
    public enum Transition { INTENT, DISPATCH, UNKNOWN, OUTCOME, MANUAL, COMPENSATION }

    public AuditSource {
        if(schemaVersion!=1) throw new IllegalArgumentException("unsupported audit source schema");
        scopeFingerprint=Checks.requireMatching("audit scope fingerprint",scopeFingerprint,SHA256);
        keyFingerprint=Checks.requireMatching("audit key fingerprint",keyFingerprint,SHA256);
        Objects.requireNonNull(transition,"transition");
        Checks.requireInRange("source sequence",sequence,1,Long.MAX_VALUE);
        contentFingerprint=Checks.requireMatching("audit draft fingerprint",contentFingerprint,SHA256);
    }

    /**
     * Logical source identity, deliberately excluding content. Same key with changed content is a conflict, never a
     * new event. This key is also separate from the retained ULID of the draft and from effect/credential identities.
     */
    public String key() {
        return "as1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.audit.source.v1").number(schemaVersion)
                .text(scopeFingerprint).text(keyFingerprint).text(transition.name()).number(sequence).toBytes());
    }
}
