package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.AGENT;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.NOW;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.InMemoryAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.ScriptedEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** WP-106: the closed human-action vocabulary and its deterministic rules run before the policy engine. */
class PepHumanActionTest {

    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject BOB = new Subject(SubjectKind.USER, "bob");
    private static final Set<String> ALL = Set.of("human-task:decide", "human-task:route", "human-task:cancel");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ScriptedEngine engine = new ScriptedEngine(r -> allow());
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final Pep pep = new Pep(engine, audit, Duration.ofSeconds(5), CLOCK);

    private static Decision allow() {
        return new Decision("test", DecisionOutcome.ALLOW, "ok", new PolicyRef("p", "1"), NOW);
    }

    private static HumanTask task(HumanTaskStatus status, Subject assignee) {
        return new HumanTask(Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ"), TASK_ID, "Approve", "Check it.", status,
                assignee, NOW.minusSeconds(60), null);
    }

    private static Pep pepWith(ScriptedEngine e) {
        return new Pep(e, new InMemoryAuditSink(), Duration.ofSeconds(5), CLOCK);
    }

    private void assertDeniedWithoutEngine(Decision d, String reasonPart) {
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertTrue(d.reason().contains(reasonPart), d.reason());
        assertTrue(engine.received().isEmpty(), "the engine must not be called for a deterministic refusal");
        assertEquals(1, audit.records().size(), "the refusal is recorded");
    }

    @Test
    void theAssigneeWithTheScopeMayDecideAndTheEngineIsAsked() {
        Decision d = pep.humanTaskAction(ALICE, ALL, task(HumanTaskStatus.IN_PROGRESS, ALICE), HumanAction.DECIDE);
        assertTrue(d.allows());
        assertEquals(1, engine.received().size());
        assertEquals(ActionKind.HUMAN_TASK_ACTION, engine.received().get(0).action());
        assertEquals("human-task:01BX5ZZKBKACTAV9WEVGEMMVRZ:decide", engine.received().get(0).resource());
    }

    @Test
    void anAgentOrServiceNeverActsOnAHumanTask() {
        for (SubjectKind kind : SubjectKind.values()) {
            if (kind == SubjectKind.USER) {
                continue;
            }
            ScriptedEngine e = new ScriptedEngine(r -> allow());
            Decision d = pepWith(e).humanTaskAction(new Subject(kind, "x"), ALL, task(HumanTaskStatus.OPEN, null),
                    HumanAction.CANCEL);
            assertFalse(d.allows());
            assertTrue(e.received().isEmpty(), kind + " reached the engine");
        }
        assertDeniedWithoutEngine(pep.humanTaskAction(AGENT, ALL, task(HumanTaskStatus.OPEN, null),
                HumanAction.ROUTE), "only a person");
    }

    @Test
    void aMissingScopeIsDeniedForEachAction() {
        for (HumanAction action : HumanAction.values()) {
            ScriptedEngine e = new ScriptedEngine(r -> allow());
            Decision d = pepWith(e).humanTaskAction(ALICE, Set.of("human-task:other"),
                    task(HumanTaskStatus.IN_PROGRESS, ALICE), action);
            assertFalse(d.allows());
            assertTrue(d.reason().contains(action.requiredScope()));
            assertTrue(e.received().isEmpty());
        }
    }

    @Test
    void aScopeForOneActionDoesNotCoverAnother() {
        assertDeniedWithoutEngine(pep.humanTaskAction(ALICE, Set.of("human-task:route"),
                task(HumanTaskStatus.IN_PROGRESS, ALICE), HumanAction.DECIDE), "missing scope human-task:decide");
    }

    @Test
    void onlyTheAssigneeMayDecide() {
        assertDeniedWithoutEngine(pep.humanTaskAction(BOB, ALL, task(HumanTaskStatus.IN_PROGRESS, ALICE),
                HumanAction.DECIDE), "assignee");
    }

    @Test
    void nobodyMayDecideATaskNobodyHasTaken() {
        assertDeniedWithoutEngine(pep.humanTaskAction(ALICE, ALL, task(HumanTaskStatus.OPEN, null),
                HumanAction.DECIDE), "assignee");
    }

    @Test
    void aFinishedTaskAcceptsNoAction() {
        for (HumanTaskStatus status : new HumanTaskStatus[] {HumanTaskStatus.COMPLETED, HumanTaskStatus.CANCELLED,
                HumanTaskStatus.EXPIRED}) {
            Subject assignee = status == HumanTaskStatus.COMPLETED ? ALICE : null;
            ScriptedEngine e = new ScriptedEngine(r -> allow());
            Pep p = pepWith(e);
            for (HumanAction action : HumanAction.values()) {
                assertFalse(p.humanTaskAction(ALICE, ALL, task(status, assignee), action).allows(),
                        status + " " + action);
            }
            assertTrue(e.received().isEmpty(), status + " reached the engine");
        }
    }

    @Test
    void anotherPersonMayRouteOrCancelAnOpenTaskWithTheScope() {
        assertTrue(pep.humanTaskAction(BOB, ALL, task(HumanTaskStatus.OPEN, null), HumanAction.ROUTE).allows());
        assertTrue(pep.humanTaskAction(BOB, ALL, task(HumanTaskStatus.IN_PROGRESS, ALICE), HumanAction.CANCEL)
                .allows());
    }

    @Test
    void anEngineDenyOrErrorStopsTheActionAfterTheRules() {
        ScriptedEngine denying = new ScriptedEngine(
                r -> new Decision("test", DecisionOutcome.DENY, "no", new PolicyRef("p", "1"), NOW));
        assertFalse(pepWith(denying).humanTaskAction(ALICE, ALL, task(HumanTaskStatus.IN_PROGRESS, ALICE),
                HumanAction.DECIDE).allows());
        ScriptedEngine throwing = new ScriptedEngine(r -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(DecisionOutcome.ERROR, pepWith(throwing).humanTaskAction(ALICE, ALL,
                task(HumanTaskStatus.IN_PROGRESS, ALICE), HumanAction.DECIDE).outcome());
    }

    @Test
    void aRefusalWhoseAuditWriteFailsIsStillRefused() {
        Pep p = new Pep(engine, record -> {
            throw new IllegalStateException("audit down");
        }, Duration.ofSeconds(5), CLOCK);
        Decision d = p.humanTaskAction(AGENT, ALL, task(HumanTaskStatus.OPEN, null), HumanAction.CANCEL);
        assertFalse(d.allows());
        assertTrue(engine.received().isEmpty());
    }
}
