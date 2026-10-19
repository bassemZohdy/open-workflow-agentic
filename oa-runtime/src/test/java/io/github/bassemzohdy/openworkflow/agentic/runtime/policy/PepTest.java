package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.AGENT;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.GRANT_ID;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.NOW;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.OPERATOR;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.InMemoryAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.ScriptedEngine;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.SafeRecovery.RecoveryOperation;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The enforcement point: the four boundaries, the guard, refusals and audit. */
class PepTest {

    static final PolicyRef PERMIT = new PolicyRef("tools.permit-scoped-call", "2026-10-18.1");
    static final Clock CLOCK = Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    static Decision allow(AuthorizationRequest r) {
        return new Decision("test-engine", DecisionOutcome.ALLOW, "allowed", PERMIT, NOW);
    }

    static Decision deny(AuthorizationRequest r) {
        return new Decision("test-engine", DecisionOutcome.DENY, "denied", PERMIT, NOW);
    }

    static Decision error(AuthorizationRequest r) {
        return new Decision("test-engine", DecisionOutcome.ERROR, "engine broke", PolicyRef.FAIL_CLOSED, NOW);
    }

    static Pep pep(ScriptedEngine engine, InMemoryAuditSink audit) {
        return new Pep(engine, audit, Duration.ofSeconds(5), CLOCK);
    }

    @Test
    void toolCallBuildsTheRequestFromTheApprovedContractAndTheCall() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        Pep pep = pep(engine, new InMemoryAuditSink());

        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(RiskTier.MEDIUM, Set.of("search")), "search", 500L);

        assertEquals(DecisionOutcome.ALLOW, d.outcome());
        AuthorizationRequest r = engine.received().get(0);
        assertEquals(AGENT, r.subject());
        assertEquals(ActionKind.TOOL_CALL, r.action());
        assertEquals("tool:search", r.resource());
        assertEquals(TASK_ID, r.taskId());
        assertEquals(Set.of("cases:read"), r.context().scopes());
        assertEquals(RiskTier.MEDIUM, r.context().riskTier());
        assertEquals(500L, r.context().budgetRemainingMicros());
        assertEquals(USER, r.context().onBehalfOf());
        assertEquals(GRANT_ID, r.context().delegationGrantId());
        assertEquals(Boolean.TRUE, r.context().permittedByContract());
    }

    @Test
    void aToolOutsideTheAllowListIsMarkedNotPermittedForThePolicy() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::deny);
        Decision d = pep(engine, new InMemoryAuditSink()).toolCall(AGENT, PolicyFixtures.task(), "delete", 500L);
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertEquals(Boolean.FALSE, engine.received().get(0).context().permittedByContract());
    }

    @Test
    void aModelCallHasNoContractAllowListSoPermissionIsUnknown() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        pep(engine, new InMemoryAuditSink()).modelCall(AGENT, PolicyFixtures.task(), "small-fast", 900L);
        AuthorizationRequest r = engine.received().get(0);
        assertEquals(ActionKind.MODEL_CALL, r.action());
        assertEquals("model:small-fast", r.resource());
        assertNull(r.context().permittedByContract());
        assertEquals(900L, r.context().budgetRemainingMicros());
    }

    @Test
    void aHumanTaskActionIsAttributedToTheWaitingAgentTask() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        Decision d = pep(engine, new InMemoryAuditSink()).humanTaskAction(OPERATOR, Set.of("tasks:act"),
                PolicyFixtures.humanTask(TASK_ID), "approve");
        assertEquals(DecisionOutcome.ALLOW, d.outcome());
        AuthorizationRequest r = engine.received().get(0);
        assertEquals(ActionKind.HUMAN_TASK_ACTION, r.action());
        assertEquals("human-task:01BX5ZZKBKACTAV9WEVGEMMVRZ:approve", r.resource());
        assertEquals(TASK_ID, r.taskId());
        assertEquals(Set.of("tasks:act"), r.context().scopes());
    }

    @Test
    void aHumanTaskActionVerbMustBeAToken() {
        Pep pep = pep(new ScriptedEngine(PepTest::allow), new InMemoryAuditSink());
        assertThrows(IllegalArgumentException.class, () -> pep.humanTaskAction(OPERATOR, Set.of(),
                PolicyFixtures.humanTask(TASK_ID), "Approve Now"));
    }

    @Test
    void aPublicationCarriesTheArtifactDigestInTheResource() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        pep(engine, new InMemoryAuditSink()).publication(OPERATOR, Set.of("registry:publish"),
                PolicyFixtures.artifact());
        AuthorizationRequest r = engine.received().get(0);
        assertEquals(ActionKind.PUBLICATION, r.action());
        assertEquals("artifact:POLICY_BUNDLE/tool-calls@1.4.0#" + "a".repeat(64), r.resource());
        assertNull(r.taskId());
    }

    @Test
    void guardRunsTheCallOnlyOnAllow() {
        Pep pep = pep(new ScriptedEngine(PepTest::allow), new InMemoryAuditSink());
        AtomicInteger calls = new AtomicInteger();
        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
        String result = Pep.guard(d, () -> {
            calls.incrementAndGet();
            return "searched";
        });
        assertEquals("searched", result);
        assertEquals(1, calls.get());
    }

    @Test
    void guardNeverRunsTheCallOnDenyOrError() {
        for (ScriptedEngine engine : new ScriptedEngine[] {new ScriptedEngine(PepTest::deny),
                new ScriptedEngine(PepTest::error)}) {
            Pep pep = pep(engine, new InMemoryAuditSink());
            AtomicInteger calls = new AtomicInteger();
            Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
            PolicyDenied denied = assertThrows(PolicyDenied.class, () -> Pep.guard(d, () -> {
                calls.incrementAndGet();
                return "searched";
            }));
            assertFalse(denied.decision().allows());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void anEngineThatThrowsIsARefusalAndTheCallDoesNotRun() {
        Pep pep = pep(new ScriptedEngine(r -> {
            throw new IllegalStateException("engine crashed with secret detail");
        }), new InMemoryAuditSink());
        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
        assertEquals(DecisionOutcome.ERROR, d.outcome());
        assertFalse(d.reason().contains("secret"), d.reason());
        assertThrows(PolicyDenied.class, () -> Pep.guard(d, () -> "ran"));
    }

    @Test
    void anEngineThatReturnsNothingIsARefusal() {
        Pep pep = pep(new ScriptedEngine(r -> null), new InMemoryAuditSink());
        assertEquals(DecisionOutcome.ERROR, pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L).outcome());
    }

    @Test
    void anEngineThatIsSlowerThanTheTimeoutIsARefusal() {
        ScriptedEngine slow = new ScriptedEngine(r -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return allow(r);
        });
        Pep pep = new Pep(slow, new InMemoryAuditSink(), Duration.ofMillis(100), CLOCK);
        long started = System.nanoTime();
        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(DecisionOutcome.ERROR, d.outcome());
        assertTrue(elapsedMillis < 1_500, "decision waited " + elapsedMillis + " ms");
    }

    @Test
    void everyDecisionIsAuditedIncludingRefusals() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        ScriptedEngine engine = new ScriptedEngine(r -> r.resource().equals("tool:search") ? allow(r) : deny(r));
        Pep pep = pep(engine, audit);
        Decision allowed = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L);
        Decision refused = pep.toolCall(AGENT, PolicyFixtures.task(), "delete", 1L);

        assertEquals(2, audit.records().size());
        AuditEvent first = audit.records().get(0);
        AuditEvent second = audit.records().get(1);
        assertEquals("ALLOW", first.attributes().get("policy.outcome"));
        assertEquals("DENY", second.attributes().get("policy.outcome"));
        assertEquals("false", first.attributes().get("policy.recoveryPath"));
        assertEquals("false", second.attributes().get("policy.recoveryPath"));
        assertEquals("tool:delete", second.resourceId());
    }

    @Test
    void thePepUsesItsExplicitBulkheadBound() throws Exception {
        CountDownLatch delegateEntered = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        ScriptedEngine engine = new ScriptedEngine(request -> {
            delegateEntered.countDown();
            try {
                releaseDelegate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test delegate interrupted", e);
            }
            return allow(request);
        });
        Pep pep = new Pep(engine, new InMemoryAuditSink(), Duration.ofSeconds(5), CLOCK, 1);
        Thread first = Thread.ofPlatform().start(
                () -> pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L));
        try {
            assertTrue(delegateEntered.await(2, TimeUnit.SECONDS));
            Decision overflow = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
            assertEquals(DecisionOutcome.ERROR, overflow.outcome());
            assertTrue(overflow.reason().contains("bulkhead"), overflow.reason());
            assertEquals(1, engine.received().size(), "a full PEP bulkhead does not call its delegate");
        } finally {
            releaseDelegate.countDown();
        }
        first.join(2_000);
        assertFalse(first.isAlive(), "the admitted decision should finish after its delegate is released");
    }

    @Test
    void decisionsAreAttributedToThePolicyThatDecidedThem() {
        Pep pep = pep(new ScriptedEngine(PepTest::allow), new InMemoryAuditSink());
        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L);
        assertEquals(PERMIT, d.policy());
        assertEquals("test-engine", d.decidedBy());
    }

    @Test
    void aDecisionThatCannotBeRecordedIsRefusedEvenWhenItWasAnAllow() {
        InMemoryAuditSink broken = new InMemoryAuditSink() {
            @Override
            public void record(AuditEvent record) {
                throw new IllegalStateException("audit store unavailable");
            }
        };
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        Pep pep = pep(engine, broken);
        AtomicInteger calls = new AtomicInteger();
        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L);

        assertEquals(DecisionOutcome.ERROR, d.outcome());
        assertEquals(1, engine.received().size(), "the engine was asked, the audit failed afterwards");
        assertThrows(PolicyDenied.class, () -> Pep.guard(d, () -> {
            calls.incrementAndGet();
            return "ran";
        }));
        assertEquals(0, calls.get());
    }

    @Test
    void theRecoveryRuleIsNeverConsultedForAnOrdinaryCall() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Pep pep = pep(new ScriptedEngine(PepTest::error), audit);
        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 1L);
        assertEquals(DecisionOutcome.ERROR, d.outcome());
        assertEquals(1, audit.records().size());
        assertEquals("false", audit.records().get(0).attributes().get("policy.recoveryPath"));
    }

    @Test
    void operationsAreBuiltFromTheOperatorAndTheTask() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        pep(engine, new InMemoryAuditSink()).operation(RecoveryOperation.PAUSE, OPERATOR, Set.of("oa.recover"),
                TASK_ID);
        AuthorizationRequest r = engine.received().get(0);
        assertEquals(ActionKind.OPERATION, r.action());
        assertEquals("operation:pause", r.resource());
        assertEquals(TASK_ID, r.taskId());
        assertEquals(OPERATOR, r.subject());
    }
}
