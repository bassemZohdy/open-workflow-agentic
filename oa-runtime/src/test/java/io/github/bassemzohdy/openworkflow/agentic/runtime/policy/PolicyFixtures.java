package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared fixtures: a reference policy set, an approved task, subjects, and a snapshot writer. */
final class PolicyFixtures {

    static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    static final Ulid TASK_ID = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    static final Ulid GRANT_ID = Ulid.parse("01CP2ZZKBKACTAV9WEVGEMMVRZ");
    static final Subject AGENT = new Subject(SubjectKind.AGENT, "research-agent");
    static final Subject USER = new Subject(SubjectKind.USER, "bassem");
    static final Subject OPERATOR = new Subject(SubjectKind.USER, "ops-lead");
    static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "case-workflow-7");

    static final String V1 = "2026-10-18.1";
    static final String V2 = "2026-10-18.2";

    /** Tool calls: a permit with three conditions, and a forbid for high-risk tasks that overrides it. */
    static final String TOOLS = """
            @id("tools.permit-scoped-call")
            permit (principal is Agent, action == Action::"tool_call", resource)
            when {
                context.scopes.contains("cases:read") &&
                context.budgetRemainingMicros > 0 &&
                context.permittedByContract == true
            };

            @id("tools.forbid-high-risk-call")
            forbid (principal, action == Action::"tool_call", resource)
            when { context.riskTier == "HIGH" };
            """;

    static final String MODELS = """
            @id("models.permit-budgeted-call")
            permit (principal is Agent, action == Action::"model_call", resource)
            when { context.budgetRemainingMicros > 0 };
            """;

    static final String OPERATIONS = """
            @id("operations.permit-operator")
            permit (principal is User, action == Action::"operation", resource)
            when { context.scopes.contains("oa.recover") };
            """;

    static final String PUBLICATION = """
            @id("publication.permit-registry-publisher")
            permit (principal is User, action == Action::"publication", resource)
            when { context.scopes.contains("registry:publish") };
            """;

    static final String HUMAN_TASKS = """
            @id("human-task.permit-assignee")
            permit (principal is User, action == Action::"human_task_action", resource)
            when { context.scopes.contains("tasks:act") };
            """;

    static List<String> referenceSet() {
        return List.of(TOOLS, MODELS, OPERATIONS, PUBLICATION, HUMAN_TASKS);
    }

    /** Writes a snapshot folder: one file per text, and a manifest that declares the hash of the texts. */
    static void writeSnapshot(Path dir, String version, List<String> texts) throws IOException {
        Files.createDirectories(dir);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String name = "policy-" + i + ".cedar";
            Files.writeString(dir.resolve(name), texts.get(i), StandardCharsets.UTF_8);
            names.add(name);
        }
        String hash = PolicySnapshot.hashOf(version, texts);
        Files.writeString(dir.resolve(ManifestPolicySnapshotSource.MANIFEST),
                "version=" + version + "\nsha256=" + hash + "\npolicies=" + String.join(",", names) + "\n",
                StandardCharsets.UTF_8);
    }

    static PublishedPolicySnapshot published(String version, List<String> texts) {
        return new PublishedPolicySnapshot(version, PolicySnapshot.hashOf(version, texts), texts);
    }

    static AgentTaskContract task(RiskTier tier, Set<String> tools) {
        return new AgentTaskContract(TASK_ID, "Summarise the open case for the officer", tools,
                Set.of("cases:read"), tier,
                new ExecutionLimits(20, Duration.ofMinutes(10), 1_000_000L), USER, GRANT_ID);
    }

    static AgentTaskContract task() {
        return task(RiskTier.LOW, Set.of("search"));
    }

    static HumanTask humanTask(Ulid agentTaskId) {
        return new HumanTask(Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ"), agentTaskId, "Approve the refund",
                "Check the amount against the policy limit.", HumanTaskStatus.OPEN, null, NOW.minusSeconds(60), null);
    }

    static ArtifactRef artifact() {
        return new ArtifactRef(ArtifactKind.POLICY_BUNDLE, "tool-calls", "1.4.0",
                "a".repeat(64));
    }

    private PolicyFixtures() {
    }
}
