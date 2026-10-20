package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.*;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable association DATA, not proof a graph completed, that a checkpoint was persisted, or permission to release
 * private output. The trusted producer first verifies the exact terminal physical run/checkpoint in one consistent
 * store read and atomically retains this association for the same call/entry. Released threads may also be errors.
 * Backend checkpoint rows support updates: changed/missing content or ids must refuse, never fall back to latest/run.
 * No raw result, credential, policy handle, target request or serialized state is retained in this value.
 */
public record ResourceExecutionOutcome(ResourceExecutionEntry entry,String profile,UUID runId,UUID checkpointId,
                                       String contentType,String contentFingerprint) {
    private static final Pattern SHA256=Pattern.compile("[0-9a-f]{64}");
    public ResourceExecutionOutcome {
        Objects.requireNonNull(entry,"entry");Objects.requireNonNull(runId,"runId");Objects.requireNonNull(checkpointId,"checkpointId");
        if(!ResearchOutputFingerprint.PROFILE.equals(profile) || !ResearchOutputFingerprint.CONTENT_TYPE.equals(contentType))
            throw new IllegalArgumentException("unsupported retained graph profile");
        contentFingerprint=Checks.requireMatching("retained graph fingerprint",contentFingerprint,SHA256);
    }
    /** Compares retained data only. Caller must supply actual trusted store values, then refresh its private live fence. */
    public void requireMatches(ResourceExecutionEntry expected,ThreadId storedThread,UUID storedRun,UUID storedCheckpoint,
                               String storedContentType,String storedBase64) {
        Objects.requireNonNull(expected,"expected");
        if(!entry.equals(expected) || !entry.threadId().equals(storedThread) || !runId.equals(storedRun)
                || !checkpointId.equals(storedCheckpoint) || !contentType.equals(storedContentType)
                || !contentFingerprint.equals(ResearchOutputFingerprint.of(storedContentType,storedBase64)))
            throw new IllegalStateException("retained resource outcome differs from its bound execution");
    }
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.execution-outcome.v1")
                .text(entry.fingerprint()).text(profile).text(runId.toString()).text(checkpointId.toString())
                .text(contentType).text(contentFingerprint).toBytes());
    }
}
