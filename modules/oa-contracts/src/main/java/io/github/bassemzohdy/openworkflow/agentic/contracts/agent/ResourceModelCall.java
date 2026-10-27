package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelCall;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One trusted complete graph-node occurrence. Node identity excludes credentials, SDK retries, owner epochs and
 * request content: changes conflict at the same original node. The producer supplies real enclosing loop/branch
 * coordinates, never a caller nonce. DATA only; constructor/fingerprint/executor UUID is not current permission.
 */
public record ResourceModelCall(ResourceExecutionEntry entry, UUID executor, String nodeOccurrence,
                                 String requestFingerprint, int maxOutputTokens) {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    public ResourceModelCall {
        Objects.requireNonNull(entry, "entry"); Objects.requireNonNull(executor, "executor");
        nodeOccurrence = Checks.requireText("canonical model node occurrence", nodeOccurrence, 1024);
        CanonicalEncoder.strictUtf8(nodeOccurrence);
        requestFingerprint = Checks.requireMatching("model request fingerprint", requestFingerprint, HASH);
        Checks.requireInRange("maxOutputTokens", maxOutputTokens, 1, ModelCall.MAX_OUTPUT_TOKENS);
    }
    public static ResourceModelCall of(AllocatedResourceTask allocation, ResourceExecutionEntry entry, UUID executor,
                                      String nodeOccurrence, ModelCall call) {
        Objects.requireNonNull(allocation, "allocation"); Objects.requireNonNull(call, "call");
        if (!allocation.allocation().scope().equals(entry.scope())
                || !allocation.allocation().contract().id().equals(call.taskId())
                || !allocation.allocation().modelProfile().modelId().equals(call.modelId())
                || call.maxOutputTokens() > allocation.allocation().modelProfile().maxOutputTokens())
            throw new IllegalArgumentException("model call differs from the original task/profile");
        return new ResourceModelCall(entry, executor, nodeOccurrence, requestFingerprint(call), call.maxOutputTokens());
    }
    /** Exact private request; no raw prompt is retained by the reservation. No normalization or guessed usage. */
    public static String requestFingerprint(ModelCall call) {
        Objects.requireNonNull(call, "call");
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.model.node-request.v1")
                .text(call.taskId().value()).text(call.modelId()).text(call.prompt()).number(call.maxOutputTokens()).toBytes());
    }
    public String key() {
        return "mn1-" + CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.model-node.v1")
                .text(entry.callKey()).text(nodeOccurrence).toBytes());
    }
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.model-node-contract.v1")
                .text(key()).text(entry.fingerprint()).text(executor.toString()).text(requestFingerprint)
                .number(maxOutputTokens).toBytes());
    }
}
