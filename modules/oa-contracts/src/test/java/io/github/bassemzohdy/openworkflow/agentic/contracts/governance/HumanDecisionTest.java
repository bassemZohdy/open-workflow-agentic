package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import org.junit.jupiter.api.Test;

class HumanDecisionTest {
    private static final Ulid ID = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final Ulid TASK = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW");
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");

    @Test
    void exactRetriesMatchAndEveryDecisionFactChangesTheFingerprint() {
        var original = new HumanDecision(ID, "tenant", TASK, 1, ALICE, HumanDecision.Outcome.APPROVE, "private");
        assertEquals(original.fingerprint(), new HumanDecision(ID, "tenant", TASK, 1, ALICE,
                HumanDecision.Outcome.APPROVE, "private").fingerprint());
        assertNotEquals(original.fingerprint(), new HumanDecision(TASK, "tenant", TASK, 1, ALICE,
                HumanDecision.Outcome.APPROVE, "private").fingerprint());
        assertNotEquals(original.fingerprint(), new HumanDecision(ID, "tenant", ID, 1, ALICE,
                HumanDecision.Outcome.APPROVE, "private").fingerprint());
        assertNotEquals(original.fingerprint(), new HumanDecision(ID, "tenant", TASK, 1, ALICE,
                HumanDecision.Outcome.REJECT, "private").fingerprint());
        assertNotEquals(original.fingerprint(), new HumanDecision(ID, "tenant", TASK, 2, ALICE,
                HumanDecision.Outcome.APPROVE, "private").fingerprint());
        assertNotEquals(original.fingerprint(), new HumanDecision(ID, "other", TASK, 1, ALICE,
                HumanDecision.Outcome.APPROVE, "private").fingerprint());
        assertNotEquals(original.fingerprint(), new HumanDecision(ID, "tenant", TASK, 1,
                new Subject(SubjectKind.USER, "bob"), HumanDecision.Outcome.APPROVE, "private").fingerprint());
        assertNotEquals(original.fingerprint(), new HumanDecision(ID, "tenant", TASK, 1, ALICE,
                HumanDecision.Outcome.APPROVE, "changed").fingerprint());
        assertFalse(original.toString().contains("private"));
        assertFalse(original.toString().contains("alice"));
    }

    @Test
    void rejectsNonHumanActorsAndAmbiguousOrOversizedText() {
        assertThrows(IllegalArgumentException.class, () -> new HumanDecision(ID, "tenant", TASK, 0,
                new Subject(SubjectKind.AGENT, "agent"), HumanDecision.Outcome.APPROVE, null));
        assertThrows(IllegalArgumentException.class, () -> new HumanDecision(ID, "tenant", TASK, -1, ALICE,
                HumanDecision.Outcome.APPROVE, null));
        assertThrows(IllegalArgumentException.class, () -> new HumanDecision(ID, "tenant", TASK, 0, ALICE,
                HumanDecision.Outcome.APPROVE, "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> new HumanDecision(ID, "tenant", TASK, 0, ALICE,
                HumanDecision.Outcome.APPROVE, "x".repeat(HumanDecision.MAX_CONTENT_LENGTH + 1)));
        var empty = new HumanDecision(ID, "tenant", TASK, 0, ALICE, HumanDecision.Outcome.REJECT, "");
        var absent = new HumanDecision(ID, "tenant", TASK, 0, ALICE, HumanDecision.Outcome.REJECT, null);
        assertNotEquals(empty.fingerprint(), absent.fingerprint());
    }
}
