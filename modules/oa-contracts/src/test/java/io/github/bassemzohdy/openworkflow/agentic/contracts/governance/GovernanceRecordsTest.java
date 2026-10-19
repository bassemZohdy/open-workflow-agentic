package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.EVIDENCE_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.NOW;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GovernanceRecordsTest {

    private static final ThreadId THREAD = ThreadId.derive("acme", "user-42", "wf-instance-9");

    // Decision

    @Test
    void decisionCarriesAttributionAndPolicyVersion() {
        Decision d = Samples.decision();
        assertEquals("cedar-embedded", d.decidedBy());
        assertEquals("2026.10.1", d.policy().policySetVersion());
        assertEquals("pol.tools.case-search", d.policy().policyId());
        assertFalse(d.allows());
    }

    @Test
    void onlyAllowPermits() {
        assertTrue(DecisionOutcome.ALLOW.permits());
        assertFalse(DecisionOutcome.DENY.permits());
        assertFalse(DecisionOutcome.ERROR.permits());
    }

    @Test
    void decisionRequiresAllFields() {
        PolicyRef policy = new PolicyRef("p", "1");
        assertThrows(IllegalArgumentException.class, () -> new Decision(" ", DecisionOutcome.ALLOW, "r", policy, NOW));
        assertThrows(NullPointerException.class, () -> new Decision("x", null, "r", policy, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Decision("x", DecisionOutcome.ALLOW, "",
                policy, NOW));
        assertThrows(NullPointerException.class, () -> new Decision("x", DecisionOutcome.ALLOW, "r", null, NOW));
        assertThrows(NullPointerException.class, () -> new Decision("x", DecisionOutcome.ALLOW, "r", policy, null));
        assertThrows(IllegalArgumentException.class, () -> new Decision("x", DecisionOutcome.ALLOW,
                "r".repeat(Decision.MAX_REASON_LENGTH + 1), policy, NOW));
    }

    @Test
    void policyRefNeedsIdAndVersion() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyRef("", "1"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRef("p", " "));
        assertEquals("oa.fail-closed", PolicyRef.FAIL_CLOSED.policyId());
    }

    // Effect

    @Test
    void effectStateMovesFollowTheLedgerRules() {
        assertTrue(EffectState.INTENDED.canMoveTo(EffectState.CONFIRMED));
        assertTrue(EffectState.INTENDED.canMoveTo(EffectState.FAILED));
        assertFalse(EffectState.INTENDED.canMoveTo(EffectState.INTENDED));
        assertFalse(EffectState.INTENDED.canMoveTo(EffectState.COMPENSATED));
        assertTrue(EffectState.CONFIRMED.canMoveTo(EffectState.COMPENSATED));
        assertTrue(EffectState.FAILED.canMoveTo(EffectState.COMPENSATED));
        assertFalse(EffectState.CONFIRMED.canMoveTo(EffectState.FAILED));
        assertFalse(EffectState.FAILED.canMoveTo(EffectState.CONFIRMED));
        for (EffectState next : EffectState.values()) {
            assertFalse(EffectState.COMPENSATED.canMoveTo(next), "compensated is final");
        }
    }

    @Test
    void onlyIntendedIsUnresolved() {
        assertFalse(EffectState.INTENDED.isResolved());
        assertTrue(EffectState.CONFIRMED.isResolved());
        assertTrue(EffectState.FAILED.isResolved());
        assertTrue(EffectState.COMPENSATED.isResolved());
    }

    @Test
    void effectIsKeyedByThreadNodeAndIdempotencyKey() {
        Effect e = Samples.effect();
        assertEquals(new EffectKey(THREAD, "draft", "idem-0001"), e.key());
    }

    @Test
    void effectValidatesItsFields() {
        assertThrows(IllegalArgumentException.class, () -> new Effect(THREAD, "", "k", "t",
                EffectState.INTENDED, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Effect(THREAD, "n", "k".repeat(201), "t",
                EffectState.INTENDED, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Effect(THREAD, "n", "k", " ",
                EffectState.INTENDED, null, NOW));
        assertThrows(NullPointerException.class, () -> new Effect(THREAD, "n", "k", "t", null, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Effect(THREAD, "n", "k", "t",
                EffectState.INTENDED, "d".repeat(1001), NOW));
        assertThrows(NullPointerException.class, () -> new EffectKey(null, "n", "k"));
    }

    // HumanTask

    @Test
    void humanTaskInProgressNeedsAnAssignee() {
        assertThrows(IllegalArgumentException.class, () -> new HumanTask(EVIDENCE_ID, TASK_ID, "t", "i",
                HumanTaskStatus.IN_PROGRESS, null, NOW, null));
        HumanTask done = new HumanTask(EVIDENCE_ID, TASK_ID, "t", "i", HumanTaskStatus.COMPLETED,
                USER, NOW, null);
        assertEquals(HumanTaskStatus.COMPLETED, done.status());
    }

    @Test
    void openHumanTaskMayBeUnassigned() {
        assertEquals(null, Samples.humanTask().assignee());
    }

    @Test
    void dueTimeMustBeAfterCreation() {
        assertThrows(IllegalArgumentException.class, () -> new HumanTask(EVIDENCE_ID, TASK_ID, "t", "i",
                HumanTaskStatus.OPEN, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new HumanTask(EVIDENCE_ID, TASK_ID, "t", "i",
                HumanTaskStatus.OPEN, null, NOW, NOW.minusSeconds(1)));
    }

    @Test
    void humanTaskTextIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new HumanTask(EVIDENCE_ID, TASK_ID, "x".repeat(201),
                "i", HumanTaskStatus.OPEN, null, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new HumanTask(EVIDENCE_ID, TASK_ID, "t",
                "x".repeat(4001), HumanTaskStatus.OPEN, null, NOW, null));
    }

    @Test
    void onlyInProgressAndCompletedRequireAnAssignee() {
        assertTrue(HumanTaskStatus.IN_PROGRESS.requiresAssignee());
        assertTrue(HumanTaskStatus.COMPLETED.requiresAssignee());
        assertFalse(HumanTaskStatus.OPEN.requiresAssignee());
        assertFalse(HumanTaskStatus.CANCELLED.requiresAssignee());
        assertFalse(HumanTaskStatus.EXPIRED.requiresAssignee());
    }

    // Evidence and artifacts

    @Test
    void evidenceNeedsAShaDigestAndAReference() {
        assertEquals("a".repeat(64), Samples.evidence().sha256());
        assertThrows(IllegalArgumentException.class, () -> new Evidence(EVIDENCE_ID, TASK_ID,
                EvidenceKind.TOOL_RESULT, "ref", "A".repeat(64), NOW));
        assertThrows(IllegalArgumentException.class, () -> new Evidence(EVIDENCE_ID, TASK_ID,
                EvidenceKind.TOOL_RESULT, "ref", "a".repeat(63), NOW));
        assertThrows(IllegalArgumentException.class, () -> new Evidence(EVIDENCE_ID, TASK_ID,
                EvidenceKind.TOOL_RESULT, " ", "a".repeat(64), NOW));
        assertThrows(NullPointerException.class, () -> new Evidence(EVIDENCE_ID, null,
                EvidenceKind.TOOL_RESULT, "ref", "a".repeat(64), Instant.EPOCH));
    }

    @Test
    void artifactRefNeedsAKindNameVersionAndDigest() {
        ArtifactRef ref = new ArtifactRef(ArtifactKind.PROMPT, "triage", "1.4.0", "b".repeat(64));
        assertEquals(ArtifactKind.PROMPT, ref.kind());
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef(ArtifactKind.PROMPT, "triage", "1.4.0",
                "b".repeat(10)));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRef(ArtifactKind.PROMPT, "", "1.4.0",
                "b".repeat(64)));
    }
}
