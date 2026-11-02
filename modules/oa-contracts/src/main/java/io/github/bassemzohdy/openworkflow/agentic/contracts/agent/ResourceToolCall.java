package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectCommand;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable identity data for one tool node occurrence. The producer supplies the true enclosing branch/loop
 * coordinates; request fields, credentials, retries and execution epochs are not new logical occurrences.
 * Construction is not permission, proof of current execution, profile approval or evidence that a tool was called.
 */
public record ResourceToolCall(ResourceExecutionEntry entry, UUID executor, String nodeOccurrence,
                               String toolName, String requestFingerprint) {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public ResourceToolCall {
        Objects.requireNonNull(entry, "entry"); Objects.requireNonNull(executor, "executor");
        nodeOccurrence = Checks.requireText("canonical tool node occurrence", nodeOccurrence, 1024);
        CanonicalEncoder.strictUtf8(nodeOccurrence);
        toolName = Checks.requireMatching("tool name", toolName, AgentTaskContract.TOKEN);
        requestFingerprint = Checks.requireMatching("tool request fingerprint", requestFingerprint, HASH);
    }

    /** Binds the exact request to the allocated task and tool profile; neither value grants execution permission. */
    public static ResourceToolCall of(AllocatedResourceTask allocation, ApprovedToolProfile profile,
                                      ResourceExecutionEntry entry, UUID executor, String nodeOccurrence, ToolCall call) {
        Objects.requireNonNull(allocation, "allocation"); Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(entry, "entry"); Objects.requireNonNull(call, "call");
        var task = allocation.allocation().contract();
        profile.requireWithin(task, allocation.allocation().modelProfile().agent());
        if (!allocation.allocation().scope().equals(entry.scope()) || !task.id().equals(call.taskId())
                || !task.permitsTool(call.toolName()) || !profile.tools().contains(call.toolName()))
            throw new IllegalArgumentException("tool call differs from the allocated task or profile");
        return new ResourceToolCall(entry, executor, nodeOccurrence, call.toolName(), requestFingerprint(call, profile));
    }

    /** Hashes the exact private arguments and profile without retaining their contents in the effect command. */
    public static String requestFingerprint(ToolCall call, ApprovedToolProfile profile) {
        Objects.requireNonNull(call, "call"); Objects.requireNonNull(profile, "profile");
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.tool.node-request.v1")
                .text(call.taskId().value()).text(call.toolName()).text(call.argumentsJson())
                .text(profile.fingerprint()).toBytes());
    }

    /** Stable across credential refresh, retry, worker replacement and execution epoch changes. */
    public String key() {
        return "tn1-" + CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.tool-node.v1")
                .text(entry.callKey()).text(nodeOccurrence).toBytes());
    }

    /** Full immutable attempt data; differing executor or epoch is not a new logical occurrence. */
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.tool-node-contract.v1")
                .text(key()).text(entry.fingerprint()).text(executor.toString()).text(toolName)
                .text(requestFingerprint).toBytes());
    }

    /**
     * Derives the ledger command for a trusted registered target. A changed request/profile or target must conflict
     * at the same effect key; never choose the target or occurrence from an untrusted request.
     */
    public EffectCommand effectCommand(String registeredTarget) {
        String node = "tool:" + CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.tool.effect-node.v1")
                .text(nodeOccurrence).toBytes());
        return EffectCommand.of(entry.threadId(), node, entry.callKey(), registeredTarget, requestFingerprint);
    }
}
