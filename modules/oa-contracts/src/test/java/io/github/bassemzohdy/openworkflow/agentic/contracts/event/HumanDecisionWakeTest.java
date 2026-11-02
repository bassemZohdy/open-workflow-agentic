package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HumanDecisionWakeTest {
    private static final Ulid TASK = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final Ulid DECISION = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW");
    private static final Ulid INSTANCE = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX");
    private static final HumanDecision INPUT = new HumanDecision(DECISION, "tenant", TASK, 3,
            new Subject(SubjectKind.USER, "alice"), HumanDecision.Outcome.APPROVE, "private answer");
    private static InboxWait waitAt(String position) {
        return new InboxWait("tenant", INSTANCE, "app", "engine", "ns", "def", "1", position, 1,
                HumanDecisionWake.route(TASK), HumanDecisionWake.TYPE, "0".repeat(64), Duration.ofMinutes(1));
    }

    @Test void exactEnvelopeIsStableAndContainsOnlyReferences() {
        var wait = waitAt("do/1");
        var message = HumanDecisionWake.message(INPUT, wait);
        assertEquals(message, HumanDecisionWake.message(INPUT, wait));
        assertTrue(HumanDecisionWake.matches(INPUT, wait, message));
        assertEquals(INPUT.id().value(), message.eventId());
        assertFalse(message.dataJson().contains("private"));
        assertFalse(message.dataJson().contains("alice"));
        assertFalse(message.dataJson().contains("APPROVE"));
        assertTrue(message.dataJson().contains(wait.digest()));
    }

    @Test void sharedRouteAndTypeDoNotMakeDifferentOccurrencesEquivalent() {
        var first = waitAt("do/1");
        var second = waitAt("do/2");
        var message = HumanDecisionWake.message(INPUT, first);
        assertEquals(first.route(), second.route());
        assertEquals(first.eventType(), second.eventType());
        assertFalse(HumanDecisionWake.matches(INPUT, second, message));
        var other = HumanDecisionWake.message(INPUT, second);
        assertEquals(message.source(), other.source()); assertEquals(message.eventId(), other.eventId());
        assertNotEquals(message.fingerprint(), other.fingerprint(), "an inbox must reject this identity conflict");
    }

    @Test void wrongTaskRouteOrTenantCannotProduceAnEnvelope() {
        var original = waitAt("do/1");
        var wrongRoute = new InboxWait("tenant", INSTANCE, "app", "engine", "ns", "def", "1", "do/1", 1,
                "other", HumanDecisionWake.TYPE, "0".repeat(64), Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> HumanDecisionWake.message(INPUT, wrongRoute));
        assertFalse(HumanDecisionWake.matches(INPUT, wrongRoute, HumanDecisionWake.message(INPUT, original)));
        var otherTenant = new InboxWait("other", INSTANCE, "app", "engine", "ns", "def", "1", "do/1", 1,
                HumanDecisionWake.route(TASK), HumanDecisionWake.TYPE, "0".repeat(64), Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> HumanDecisionWake.message(INPUT, otherTenant));
    }
}
