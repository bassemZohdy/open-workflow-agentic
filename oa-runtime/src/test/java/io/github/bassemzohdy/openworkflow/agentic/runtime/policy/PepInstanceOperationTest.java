package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.InMemoryAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.ScriptedEngine;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep.InstanceOperation;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.SafeRecovery.RecoveryOperation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The instance operations of the operations API (WP-105d) at the policy enforcement point: inspect, retry and recover
 * follow the main decision, and when the main decision is ERROR they are refused, because the safe-recovery rule names
 * only cancel, pause and drain.
 */
class PepInstanceOperationTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Ulid INSTANCE = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final Subject OPERATOR = new Subject(SubjectKind.USER, "ops-lead");
    private static final Set<String> RECOVERY = Set.of("oa.recover");
    private static final Set<String> INSPECT = Set.of("oa.inspect");

    private static Pep pep(ScriptedEngine engine, InMemoryAuditSink audit) {
        return new Pep(engine, audit, Duration.ofSeconds(5), CLOCK);
    }

    @Test
    void theMainDecisionDecidesInspectRetryAndRecover() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        Pep pep = pep(engine, new InMemoryAuditSink());

        for (InstanceOperation op : InstanceOperation.values()) {
            assertEquals(DecisionOutcome.ALLOW,
                    pep.instanceOperation(op, OPERATOR, INSPECT, INSTANCE).outcome(), op.name());
        }
        List<AuthorizationRequest> received = engine.received();
        assertEquals(List.of("operation:inspect", "operation:retry", "operation:recover"),
                received.stream().map(AuthorizationRequest::resource).toList());
        assertTrue(received.stream().allMatch(r -> INSTANCE.equals(r.taskId())),
                "the request names the instance as its task id");
    }

    @Test
    void inspectRetryAndRecoverAreRefusedWhenThePolicyEngineIsDown() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Pep pep = pep(new ScriptedEngine(PepTest::error), audit);

        // Even an operator with the recovery scope gets no ALLOW for these: the recovery rule does not name them.
        for (InstanceOperation op : InstanceOperation.values()) {
            Decision decision = pep.instanceOperation(op, OPERATOR, RECOVERY, INSTANCE);
            assertEquals(DecisionOutcome.ERROR, decision.outcome(), op.name());
            assertTrue(!decision.allows(), op.name());
        }
        // Each refused decision still reaches the audit sink; the recovery attempt is recorded as a refusal too.
        long recoveryRefusals = audit.records().stream()
                .filter(r -> r.recoveryPath() && r.decision().outcome() == DecisionOutcome.DENY)
                .count();
        assertEquals(3, recoveryRefusals);
    }

    @Test
    void cancelAndPauseStillUseTheRecoveryRuleWhenThePolicyEngineIsDown() {
        Pep pep = pep(new ScriptedEngine(PepTest::error), new InMemoryAuditSink());

        assertTrue(pep.operation(RecoveryOperation.CANCEL, OPERATOR, RECOVERY, INSTANCE).allows());
        assertTrue(pep.operation(RecoveryOperation.PAUSE, OPERATOR, RECOVERY, INSTANCE).allows());
        assertEquals(DecisionOutcome.ERROR,
                pep.operation(RecoveryOperation.CANCEL, OPERATOR, Set.of(), INSTANCE).outcome(),
                "without the recovery scope the engine's ERROR stands");
    }

    @Test
    void aDenyFromTheMainPolicyIsFinalForInstanceOperations() {
        Pep pep = pep(new ScriptedEngine(PepTest::deny), new InMemoryAuditSink());

        Decision retry = pep.instanceOperation(InstanceOperation.RETRY, OPERATOR, RECOVERY, INSTANCE);
        assertEquals(DecisionOutcome.DENY, retry.outcome());
        assertEquals("test-engine", retry.decidedBy());
    }
}
