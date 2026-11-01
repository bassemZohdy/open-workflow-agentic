package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.NOW;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanActionFacts;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanActionFacts.SeparationObligation;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.InMemoryAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.ScriptedEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** WP-106: the human-action rules that need the retained task facts, still before the engine and fail closed. */
class PepHumanActionFactsTest {

    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject BOB = new Subject(SubjectKind.USER, "bob");
    private static final Subject AGENT = new Subject(SubjectKind.AGENT, "research-agent");
    private static final Set<String> ALL = Set.of("human-task:decide", "human-task:route", "human-task:cancel");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Ulid TASK = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ");

    private final ScriptedEngine engine = new ScriptedEngine(r -> allow());
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final Pep pep = new Pep(engine, audit, Duration.ofSeconds(5), CLOCK);

    private static Decision allow() {
        return new Decision("test", DecisionOutcome.ALLOW, "ok", new PolicyRef("p", "1"), NOW);
    }

    private static HumanActionFacts facts(HumanTaskStatus status, Subject assignee, Subject initiator,
                                          SeparationObligation separation, Instant dueAt) {
        return new HumanActionFacts("t1", TASK, 3, status, dueAt, null, initiator, assignee, RiskTier.HIGH, separation);
    }

    private static HumanActionFacts open(Subject assignee, Subject initiator, SeparationObligation separation) {
        return facts(HumanTaskStatus.IN_PROGRESS, assignee, initiator, separation, null);
    }

    private void assertRefused(Decision d, String reasonPart) {
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertTrue(d.reason().contains(reasonPart), d.reason());
        assertEquals(PolicyRef.FAIL_CLOSED, d.policy());
        assertTrue(engine.received().isEmpty(), "a deterministic refusal makes zero engine calls");
        assertEquals(1, audit.records().size(), "the refusal is recorded");
    }

    @Test void theAssigneeInTheTasksTenantMayDecideAndTheEngineSeesTheRiskTier() {
        Decision d = pep.humanTaskAction(ALICE, "t1", ALL, open(ALICE, BOB, SeparationObligation.NONE), HumanAction.DECIDE);
        assertTrue(d.allows());
        assertEquals(1, engine.received().size());
        assertEquals("human-task:" + TASK.value() + ":decide", engine.received().get(0).resource());
        assertEquals(RiskTier.HIGH, engine.received().get(0).context().riskTier());
    }

    @Test void anActorOutsideTheTasksTenantIsRefusedEvenWithEveryScope() {
        assertRefused(pep.humanTaskAction(ALICE, "other-tenant", ALL, open(ALICE, BOB, SeparationObligation.NONE),
                HumanAction.DECIDE), "tenant");
    }

    @Test void aMissingActorTenantNeverBecomesPermission() {
        assertRefused(pep.humanTaskAction(ALICE, null, ALL, open(ALICE, BOB, SeparationObligation.NONE),
                HumanAction.DECIDE), "tenant");
    }

    @Test void aTaskAtOrPastItsDueTimeAcceptsNoAction() {
        for (Instant due : new Instant[]{NOW, NOW.minusSeconds(1)}) {
            var local = new Pep(engine, audit, Duration.ofSeconds(5), CLOCK);
            Decision d = local.humanTaskAction(ALICE, "t1", ALL,
                    facts(HumanTaskStatus.IN_PROGRESS, ALICE, BOB, SeparationObligation.NONE, due), HumanAction.DECIDE);
            assertEquals(DecisionOutcome.DENY, d.outcome());
            assertTrue(d.reason().contains("due"), d.reason());
        }
        assertTrue(engine.received().isEmpty());
    }

    @Test void aTaskJustBeforeItsDueTimeStillAcceptsActions() {
        Decision d = pep.humanTaskAction(ALICE, "t1", ALL,
                facts(HumanTaskStatus.IN_PROGRESS, ALICE, BOB, SeparationObligation.NONE, NOW.plusNanos(1)),
                HumanAction.DECIDE);
        assertTrue(d.allows());
    }

    @Test void theInitiatorMayNeitherDecideNorRouteWhenThePolicyForbidsIt() {
        var facts = open(ALICE, ALICE, SeparationObligation.INITIATOR_MUST_NOT_ACT);
        assertRefused(pep.humanTaskAction(ALICE, "t1", ALL, facts, HumanAction.DECIDE), "initiator");
    }

    @Test void routingByTheForbiddenInitiatorIsRefusedToo() {
        var facts = open(BOB, ALICE, SeparationObligation.INITIATOR_MUST_NOT_ACT);
        assertRefused(pep.humanTaskAction(ALICE, "t1", ALL, facts, HumanAction.ROUTE), "initiator");
    }

    @Test void theInitiatorMayStillWithdrawTheirOwnRequest() {
        var facts = open(BOB, ALICE, SeparationObligation.INITIATOR_MUST_NOT_ACT);
        assertTrue(pep.humanTaskAction(ALICE, "t1", ALL, facts, HumanAction.CANCEL).allows());
    }

    @Test void withoutAnObligationTheInitiatorIsNotRestrictedAndNoRuleDependsOnRiskAlone() {
        var facts = open(ALICE, ALICE, SeparationObligation.NONE); // HIGH risk, initiator is the assignee, no obligation
        assertTrue(pep.humanTaskAction(ALICE, "t1", ALL, facts, HumanAction.DECIDE).allows());
    }

    @Test void anotherPersonMayDecideWhenTheyAreTheAssigneeAndNotTheInitiator() {
        var facts = open(BOB, ALICE, SeparationObligation.INITIATOR_MUST_NOT_ACT);
        assertTrue(pep.humanTaskAction(BOB, "t1", ALL, facts, HumanAction.DECIDE).allows());
    }

    @Test void theExistingRulesStillApplyThroughTheFactsEntryPoint() {
        var ok = open(ALICE, BOB, SeparationObligation.NONE);
        assertRefused(pep.humanTaskAction(AGENT, "t1", ALL, ok, HumanAction.DECIDE), "person");
    }

    @Test void aFinishedTaskMissingScopeOrNonAssigneeIsRefused() {
        var done = facts(HumanTaskStatus.COMPLETED, ALICE, BOB, SeparationObligation.NONE, null);
        Decision d1 = pep.humanTaskAction(ALICE, "t1", ALL, done, HumanAction.DECIDE);
        assertEquals(DecisionOutcome.DENY, d1.outcome());
        Decision d2 = pep.humanTaskAction(ALICE, "t1", Set.of("human-task:route"),
                open(ALICE, BOB, SeparationObligation.NONE), HumanAction.DECIDE);
        assertEquals(DecisionOutcome.DENY, d2.outcome());
        Decision d3 = pep.humanTaskAction(BOB, "t1", ALL, open(ALICE, ALICE, SeparationObligation.NONE), HumanAction.DECIDE);
        assertEquals(DecisionOutcome.DENY, d3.outcome());
        assertTrue(engine.received().isEmpty());
    }

    @Test void anEngineDenyStillRefusesAndIsRecorded() {
        var denying = new ScriptedEngine(r -> new Decision("engine", DecisionOutcome.DENY, "no", new PolicyRef("p", "1"), NOW));
        var sink = new InMemoryAuditSink();
        var local = new Pep(denying, sink, Duration.ofSeconds(5), CLOCK);
        Decision d = local.humanTaskAction(ALICE, "t1", ALL, open(ALICE, BOB, SeparationObligation.NONE), HumanAction.DECIDE);
        assertFalse(d.allows());
        assertEquals(1, denying.received().size());
        assertEquals(1, sink.records().size());
    }

    // ---- the facts record itself -----------------------------------------------------------------------------

    @Test void everyFactIsMandatoryExceptTheOnesThatLegitimatelyHaveNone() {
        assertThrows(NullPointerException.class, () -> new HumanActionFacts("t1", TASK, 1, HumanTaskStatus.OPEN, null,
                null, null, null, RiskTier.LOW, SeparationObligation.NONE), "no initiator");
        assertThrows(NullPointerException.class, () -> new HumanActionFacts("t1", TASK, 1, HumanTaskStatus.OPEN, null,
                null, BOB, null, null, SeparationObligation.NONE), "no risk tier");
        assertThrows(NullPointerException.class, () -> new HumanActionFacts("t1", TASK, 1, HumanTaskStatus.OPEN, null,
                null, BOB, null, RiskTier.LOW, null), "no separation obligation");
        assertThrows(IllegalArgumentException.class, () -> new HumanActionFacts("t1", TASK, -1, HumanTaskStatus.OPEN,
                null, null, BOB, null, RiskTier.LOW, SeparationObligation.NONE), "negative version");
        assertThrows(IllegalArgumentException.class, () -> new HumanActionFacts("t1", TASK, 1,
                HumanTaskStatus.IN_PROGRESS, null, null, BOB, null, RiskTier.LOW, SeparationObligation.NONE),
                "an in-progress task needs an assignee");
        assertDoesNotThrow(() -> new HumanActionFacts("t1", TASK, 0, HumanTaskStatus.OPEN, null, null, BOB, null,
                RiskTier.LOW, SeparationObligation.NONE), "an open task has no assignee yet");
    }

    @Test void theFactsDescriptionNamesNoSubject() {
        String text = open(ALICE, BOB, SeparationObligation.NONE).toString();
        assertFalse(text.contains("alice") || text.contains("bob"), text);
    }
}
