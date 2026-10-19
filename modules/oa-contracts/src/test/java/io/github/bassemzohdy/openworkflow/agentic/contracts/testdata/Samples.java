package io.github.bassemzohdy.openworkflow.agentic.contracts.testdata;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Evidence;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EvidenceKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Valid sample records, shared by the unit and schema tests. Each is built through the public constructors. */
public final class Samples {

    public static final Ulid TASK_ID = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    public static final Ulid CHILD_ID = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ");
    public static final Ulid GRANT_ID = Ulid.parse("01CP2ZZKBKACTAV9WEVGEMMVRZ");
    public static final Ulid EVIDENCE_ID = Ulid.parse("01DRZ3NDEKTSV4RRFFQ69G5FAV");
    public static final Ulid EVENT_ID = Ulid.parse("01ERZ3NDEKTSV4RRFFQ69G5FAV");

    public static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    public static final String TENANT = "acme";
    public static final String WORKFLOW_INSTANCE = "wf-instance-9";
    public static final Subject USER = new Subject(SubjectKind.USER, "user-42");
    public static final String TOOL = "tool:case-search";
    public static final String SCOPE = "case.read";

    private Samples() {
    }

    public static ExecutionLimits limits() {
        return new ExecutionLimits(20, Duration.ofMinutes(10), 500_000L);
    }

    public static AgentTaskContract contract() {
        return new AgentTaskContract(TASK_ID, "Summarise open cases for the caseworker", Set.of(TOOL), Set.of(SCOPE),
                RiskTier.MEDIUM, limits(), USER, GRANT_ID);
    }

    public static AgentTaskRecord taskRecord() {
        return new AgentTaskRecord(TASK_ID, TENANT, USER, WORKFLOW_INSTANCE,
                ThreadId.derive(TENANT, USER.id(), WORKFLOW_INSTANCE), contract(), AgentTaskStatus.RUNNING, 3L);
    }

    public static Decision decision() {
        return new Decision("cedar-embedded", DecisionOutcome.DENY, "no permit covers this tool",
                new PolicyRef("pol.tools.case-search", "2026.10.1"), NOW);
    }

    public static Effect effect() {
        return new Effect(ThreadId.derive(TENANT, USER.id(), WORKFLOW_INSTANCE), "draft", "idem-0001",
                "case-system:update", EffectState.INTENDED, null, NOW);
    }

    public static HumanTask humanTask() {
        return new HumanTask(EVIDENCE_ID, TASK_ID, "Approve the case update",
                "Check the draft against the policy before it is sent.", HumanTaskStatus.OPEN, null, NOW,
                NOW.plusSeconds(3600));
    }

    public static Evidence evidence() {
        return new Evidence(EVIDENCE_ID, TASK_ID, EvidenceKind.TOOL_RESULT, "store://tool-results/abc123",
                "a".repeat(64), NOW);
    }

    /** A sealed event at sequence 1, linked to the genesis hash. */
    public static AuditEvent auditEvent() {
        return auditEventAt(1L, AuditHash.GENESIS, EVENT_ID);
    }

    /** A sealed event at {@code sequence} linked to {@code previousHash}. */
    public static AuditEvent auditEventAt(long sequence, String previousHash, Ulid id) {
        AuditEvent unsealed = new AuditEvent(id, sequence, NOW.plusSeconds(sequence), USER, "tool.call.allowed",
                "tool", TOOL, Map.of("decision", "ALLOW", "policy", "pol.tools.case-search"), previousHash,
                AuditHash.GENESIS);
        return AuditHash.seal(unsealed);
    }
}
