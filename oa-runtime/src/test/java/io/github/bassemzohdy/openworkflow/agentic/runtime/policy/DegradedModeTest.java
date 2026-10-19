package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.AGENT;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.OPERATOR;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.InMemoryAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.ScriptedEngine;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.SafeRecovery.RecoveryOperation;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Degraded mode: the main policy is missing, broken, or its engine is down. Only the three operator operations that
 * stop or hold work are allowed, by the built-in rule. Everything else is refused, and so is a deliberate DENY.
 */
class DegradedModeTest {

    static final Set<String> OPERATOR_SCOPES = Set.of(SafeRecovery.OPERATOR_SCOPE);

    private static Pep pep(PolicyDecisionPoint engine, InMemoryAuditSink audit) {
        return new Pep(engine, audit, Duration.ofSeconds(5), Clock.fixed(PolicyFixtures.NOW,
                java.time.ZoneOffset.UTC));
    }

    private static ScriptedEngine unavailable() {
        return new ScriptedEngine(PepTest::error);
    }

    private static AuthorizationRequest operation(String resource, Subject who, Set<String> scopes, Ulid task) {
        return new AuthorizationRequest(who, ActionKind.OPERATION, resource, task,
                new PolicyContext(scopes, null, null, null, null, null));
    }

    @Test
    void cancelPauseAndDrainAreAllowedForAnOperatorWhenTheMainPolicyIsUnavailable() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Pep pep = pep(unavailable(), audit);

        Decision cancel = pep.operation(RecoveryOperation.CANCEL, OPERATOR, OPERATOR_SCOPES, TASK_ID);
        Decision pause = pep.operation(RecoveryOperation.PAUSE, OPERATOR, OPERATOR_SCOPES, TASK_ID);
        Decision drain = pep.operation(RecoveryOperation.DRAIN, OPERATOR, OPERATOR_SCOPES, null);

        for (Decision d : List.of(cancel, pause, drain)) {
            assertEquals(DecisionOutcome.ALLOW, d.outcome(), d.reason());
            assertEquals(SafeRecovery.DECIDED_BY, d.decidedBy());
            assertEquals(SafeRecovery.POLICY, d.policy());
        }
    }

    @Test
    void theRecoveryAllowIsAuditedAfterTheMainErrorThatCausedIt() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        pep(unavailable(), audit).operation(RecoveryOperation.CANCEL, OPERATOR, OPERATOR_SCOPES, TASK_ID);

        List<AuditEvent> records = audit.records();
        assertEquals(2, records.size());
        assertFalse(Boolean.parseBoolean(records.get(0).attributes().get("policy.recoveryPath")));
        assertEquals(DecisionOutcome.ERROR, DecisionOutcome.valueOf(records.get(0).attributes().get("policy.outcome")));
        assertTrue(Boolean.parseBoolean(records.get(1).attributes().get("policy.recoveryPath")));
        assertEquals(DecisionOutcome.ALLOW, DecisionOutcome.valueOf(records.get(1).attributes().get("policy.outcome")));
    }

    @Test
    void everyOtherBoundaryIsRefusedWhileTheMainPolicyIsUnavailable() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Pep pep = pep(unavailable(), audit);
        Set<String> all = Set.of("cases:read", "tasks:act", "registry:publish", "oa.recover");

        List<Decision> refused = List.of(
                pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L),
                pep.modelCall(AGENT, PolicyFixtures.task(), "small-fast", 1L),
                pep.humanTaskAction(OPERATOR, all, PolicyFixtures.humanTask(TASK_ID), "approve"),
                pep.publication(OPERATOR, all, PolicyFixtures.artifact()),
                pep.decide(operationLike(ActionKind.RESUME, all)),
                pep.decide(operationLike(ActionKind.DELEGATE, all)),
                pep.decide(operationLike(ActionKind.EFFECT_COMMIT, all)),
                pep.decide(operationLike(ActionKind.DATA_ACCESS, all)));

        for (Decision d : refused) {
            assertFalse(d.allows(), d.toString());
        }
        assertTrue(audit.records().stream().noneMatch(event -> Boolean.parseBoolean(event.attributes().get("policy.recoveryPath"))),
                "the recovery rule must not be consulted for other actions");
    }

    private static AuthorizationRequest operationLike(ActionKind kind, Set<String> scopes) {
        return new AuthorizationRequest(OPERATOR, kind, "tool:search", TASK_ID,
                new PolicyContext(scopes, null, null, null, null, null));
    }

    @Test
    void anAgentIsNeverAnOperatorEvenWithTheRecoveryScope() {
        Decision d = pep(unavailable(), new InMemoryAuditSink()).decide(
                operation("operation:cancel", AGENT, OPERATOR_SCOPES, TASK_ID));
        assertFalse(d.allows());
    }

    @Test
    void anOperatorWithoutTheRecoveryScopeIsRefused() {
        Decision d = pep(unavailable(), new InMemoryAuditSink()).operation(RecoveryOperation.CANCEL, OPERATOR,
                Set.of("cases:read"), TASK_ID);
        assertFalse(d.allows());
    }

    @Test
    void cancelAndPauseNeedAnAgentTaskAndDrainNamesNone() {
        Pep pep = pep(unavailable(), new InMemoryAuditSink());
        assertFalse(pep.operation(RecoveryOperation.CANCEL, OPERATOR, OPERATOR_SCOPES, null).allows());
        assertFalse(pep.operation(RecoveryOperation.PAUSE, OPERATOR, OPERATOR_SCOPES, null).allows());
        assertFalse(pep.operation(RecoveryOperation.DRAIN, OPERATOR, OPERATOR_SCOPES, TASK_ID).allows());
    }

    @Test
    void anUnknownOperationIsRefusedAndTheMainErrorStandsAsTheReason() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Decision d = pep(unavailable(), audit).decide(
                operation("operation:delete", OPERATOR, OPERATOR_SCOPES, TASK_ID));
        assertFalse(d.allows());
        // The refusal is the main decision, because the rule did not apply; the rule's own refusal is audited.
        assertEquals(DecisionOutcome.ERROR, d.outcome());
        assertEquals("engine broke", d.reason());
        assertEquals(2, audit.records().size());
        assertTrue(Boolean.parseBoolean(audit.records().get(1).attributes().get("policy.recoveryPath")));
        assertEquals("unknown recovery operation", audit.records().get(1).attributes().get("policy.reason"));
    }

    @Test
    void aDeliberateDenyFromTheMainPolicyIsFinalAndTheRuleIsNotConsulted() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        ScriptedEngine denying = new ScriptedEngine(r -> PepTest.deny(r));
        Decision d = pep(denying, audit).operation(RecoveryOperation.CANCEL, OPERATOR, OPERATOR_SCOPES, TASK_ID);
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertEquals(1, audit.records().size());
        assertFalse(Boolean.parseBoolean(audit.records().get(0).attributes().get("policy.recoveryPath")));
    }

    @Test
    void anEngineThatThrowsCountsAsUnavailableSoRecoveryStillWorks() {
        ScriptedEngine broken = new ScriptedEngine(r -> {
            throw new IllegalStateException("engine down");
        });
        Decision d = pep(broken, new InMemoryAuditSink()).operation(RecoveryOperation.PAUSE, OPERATOR,
                OPERATOR_SCOPES, TASK_ID);
        assertEquals(DecisionOutcome.ALLOW, d.outcome());
    }

    @Test
    void theRecoveryDecisionMustBeRecordedOrItIsRefused() {
        InMemoryAuditSink auditThatFailsOnRecovery = new InMemoryAuditSink() {
            @Override
            public void record(AuditEvent record) {
                if (Boolean.parseBoolean(record.attributes().get("policy.recoveryPath"))) {
                    throw new IllegalStateException("audit down");
                }
                super.record(record);
            }
        };
        Decision d = pep(unavailable(), auditThatFailsOnRecovery)
                .operation(RecoveryOperation.CANCEL, OPERATOR, OPERATOR_SCOPES, TASK_ID);
        assertFalse(d.allows());
        assertEquals(DecisionOutcome.ERROR, d.outcome());
    }

    @Test
    void withNoSnapshotEverLoadedOnlyTheRecoveryOperationsRemain() {
        // The real engine, with no snapshot ever accepted.
        CedarPolicyDecisionPoint never = new CedarPolicyDecisionPoint();
        Pep pep = pep(never, new InMemoryAuditSink());

        assertEquals(DecisionOutcome.ALLOW,
                pep.operation(RecoveryOperation.CANCEL, OPERATOR, OPERATOR_SCOPES, TASK_ID).outcome());
        assertEquals(DecisionOutcome.ALLOW,
                pep.operation(RecoveryOperation.DRAIN, OPERATOR, OPERATOR_SCOPES, null).outcome());
        assertFalse(pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L).allows());
        assertFalse(pep.modelCall(AGENT, PolicyFixtures.task(), "small-fast", 1L).allows());
        assertFalse(pep.publication(OPERATOR, Set.of("registry:publish"), PolicyFixtures.artifact()).allows());
    }

    @Test
    void theRecoveryRuleReferenceIsBuiltIn() {
        assertEquals(new PolicyRef("oa.safe-recovery", "1"), SafeRecovery.POLICY);
    }
}
