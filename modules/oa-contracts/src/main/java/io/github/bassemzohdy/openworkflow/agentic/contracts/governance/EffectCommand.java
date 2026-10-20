package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable identity and request digest of one logical operation. Occurrence is a persisted workflow step/loop
 * occurrence, never a delivery attempt or resume epoch. Callers supply a canonical request, excluding credentials.
 * This internal contract does not grant permission to execute; the adapter must enforce caller/policy boundaries.
 */
public record EffectCommand(EffectKey key, String target, String requestFingerprint) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public EffectCommand {
        Objects.requireNonNull(key, "key");
        target = Checks.requireText("target", target, Effect.MAX_TARGET_LENGTH);
        requestFingerprint = Checks.requireMatching("requestFingerprint", requestFingerprint, SHA256);
    }

    /** Derives a target-facing key stable across retries, process restarts and resume epochs. */
    public static EffectCommand of(ThreadId thread, String node, String occurrence, String target,
                                   String canonicalRequest) {
        Objects.requireNonNull(thread, "thread");
        Checks.requireText("node", node, EffectKey.MAX_NODE_ID_LENGTH);
        Checks.requireText("occurrence", occurrence, 256);
        String key = "ef1-" + CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.effect.v1")
                .text(thread.value()).text(node).text(occurrence).toBytes());
        String fingerprint = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.effect.request.v1")
                .text(Objects.requireNonNull(canonicalRequest, "canonicalRequest")).toBytes());
        return new EffectCommand(new EffectKey(thread, node, key), target, fingerprint);
    }
}
