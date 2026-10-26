package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelCall;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable registered/approved model configuration DATA, supplied by the trusted producer, never request fields.
 * The fingerprint pins configuration outside this value too (provider, location, pricing/ceiling and retry policy).
 * Construction does not establish registry approval, a compatible grant, a price guarantee or dispatch permission.
 * maxModelCalls is separate from the task's agent-loop iteration limit; neither is inferred from the other.
 */
public record ApprovedModelProfile(String id, String version, String configurationFingerprint, Subject agent,
                                   String modelId, int maxModelCalls, long perCallCeilingMicros,
                                   Duration maxCallTime, int maxOutputTokens) {
    public static final int MAX_MODEL_CALLS = 10_000;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public ApprovedModelProfile {
        id = text("profile id", id, 256);
        version = text("profile version", version, 128);
        configurationFingerprint = Checks.requireMatching("approved model configuration", configurationFingerprint, HASH);
        Objects.requireNonNull(agent, "agent");
        if (agent.kind() != SubjectKind.AGENT) throw new IllegalArgumentException("model profile requires a registered agent");
        CanonicalEncoder.strictUtf8(agent.id());
        modelId = text("model id", modelId, 128);
        Checks.requireInRange("maxModelCalls", maxModelCalls, 1, MAX_MODEL_CALLS);
        Checks.requireInRange("perCallCeilingMicros", perCallCeilingMicros, 1L, ExecutionLimits.MAX_COST_MICROS);
        Objects.requireNonNull(maxCallTime, "maxCallTime");
        if (maxCallTime.compareTo(ExecutionLimits.MIN_WALL_CLOCK) < 0 || maxCallTime.compareTo(ExecutionLimits.MAX_WALL_CLOCK) > 0)
            throw new IllegalArgumentException("model call time must be bounded between one second and 24 hours");
        Checks.requireInRange("maxOutputTokens", maxOutputTokens, 1, ModelCall.MAX_OUTPUT_TOKENS);
    }

    public void requireWithin(AgentTaskContract task) {
        Objects.requireNonNull(task, "task");
        if (perCallCeilingMicros > task.limits().maxCostMicros() || maxCallTime.compareTo(task.limits().maxWallClock()) > 0)
            throw new IllegalArgumentException("model call ceiling/time exceeds the approved task");
    }

    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.model.approved-profile.v1")
                .text(id).text(version).text(configurationFingerprint).text(agent.kind().name()).text(agent.id())
                .text(modelId).number(maxModelCalls).number(perCallCeilingMicros)
                .number(maxCallTime.getSeconds()).number(maxCallTime.getNano()).number(maxOutputTokens).toBytes());
    }

    private static String text(String field, String value, int max) {
        String text = Checks.requireText(field, value, max);
        CanonicalEncoder.strictUtf8(text);
        return text;
    }
}
