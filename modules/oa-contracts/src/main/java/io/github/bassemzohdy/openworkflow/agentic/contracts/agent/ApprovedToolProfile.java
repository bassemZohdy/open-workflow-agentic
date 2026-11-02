package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable approved tool configuration data; construction alone grants no authority. */
public record ApprovedToolProfile(String id, String version, String configurationFingerprint, Subject agent,
                                  Set<String> tools, int maxCalls, Duration maxCallTime) {
    public static final int MAX_TOOL_CALLS = 100_000;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public ApprovedToolProfile {
        id = text("tool profile id", id, 256);
        version = text("tool profile version", version, 128);
        configurationFingerprint = Checks.requireMatching("approved tool configuration", configurationFingerprint, HASH);
        Objects.requireNonNull(agent, "agent");
        if (agent.kind() != SubjectKind.AGENT) throw new IllegalArgumentException("tool profile requires an agent");
        CanonicalEncoder.strictUtf8(agent.id());
        tools = Checks.copyTokens("tools", tools, AgentTaskContract.MAX_ALLOW_LIST_SIZE, AgentTaskContract.TOKEN);
        Checks.requireInRange("maxCalls", maxCalls, 1, MAX_TOOL_CALLS);
        Objects.requireNonNull(maxCallTime, "maxCallTime");
        if (maxCallTime.compareTo(Duration.ofMillis(1)) < 0 || maxCallTime.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("tool call time must be between 1 ms and 1 minute");
    }

    public void requireWithin(AgentTaskContract task, Subject expectedAgent) {
        Objects.requireNonNull(task, "task"); Objects.requireNonNull(expectedAgent, "expectedAgent");
        if (!agent.equals(expectedAgent) || !task.permittedTools().containsAll(tools)
                || maxCallTime.compareTo(task.limits().maxWallClock()) > 0)
            throw new IllegalArgumentException("tool profile exceeds the approved task or agent");
    }

    public String fingerprint() {
        var e = new CanonicalEncoder("oa.tool.approved-profile.v1").text(id).text(version)
                .text(configurationFingerprint).text(agent.kind().name()).text(agent.id()).number(tools.size());
        tools.stream().sorted().forEach(e::text);
        return CanonicalEncoder.sha256Hex(e.number(maxCalls).number(maxCallTime.getSeconds())
                .number(maxCallTime.getNano()).toBytes());
    }

    private static String text(String field, String value, int max) {
        String result = Checks.requireText(field, value, max);
        CanonicalEncoder.strictUtf8(result);
        return result;
    }
}
