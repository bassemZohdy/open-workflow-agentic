package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HumanWakeSelectionTest {
    private static final Ulid TASK = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final HumanDecision DECISION = new HumanDecision(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"),
            "tenant", TASK, 0, new Subject(SubjectKind.USER, "alice"), HumanDecision.Outcome.APPROVE, "private");
    private static final String FILTER = "{\"with\":{\"source\":\"" + HumanDecisionWake.SOURCE
            + "\",\"type\":\"" + HumanDecisionWake.TYPE + "\"}}";
    private static final String SNAPSHOT = "{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"one\":" + FILTER + "}}}}";

    private static InboxWait waitAt(String position) {
        return new InboxWait("tenant", Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"), "app", "engine", "ns", "def", "1",
                position, 1, HumanDecisionWake.route(TASK), HumanDecisionWake.TYPE, "0".repeat(64), Duration.ofMinutes(1));
    }
    private static InboxListenGroup group(InboxWait wait, String filter, String snapshot) {
        String hash = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.event-filter.v1").text(filter).toBytes());
        return new InboxListenGroup(wait.task(), InboxListenGroup.Strategy.ONE, List.of(hash), InboxListenGroup.Stop.NONE,
                List.of(), null, InboxListenGroup.snapshotFingerprint(snapshot));
    }

    @Test void selectsExactEnvelopeOnlyWhenLiteralFilterAlsoMatches() {
        var wait = waitAt("do/1"); var group = group(wait, FILTER, SNAPSHOT);
        var message = HumanDecisionWake.message(DECISION, wait);
        var selection = HumanWakeSelection.select(DECISION, wait, group, SNAPSHOT, message).orElseThrow();
        assertEquals(group.slots(InboxListenGroup.Role.DATA).getFirst(), selection.slots().iterator().next());
    }

    @Test void sameRouteAndLiteralFilterCannotAuthorizeAnotherOccurrence() {
        var first = waitAt("do/1"); var second = waitAt("do/2");
        var message = HumanDecisionWake.message(DECISION, first);
        var otherGroup = group(second, FILTER, SNAPSHOT);
        assertFalse(new SdkListenMatchSet(otherGroup, SNAPSHOT).matches(message).slots().isEmpty(),
                "literal CloudEvent filters alone do not distinguish occurrences");
        assertTrue(HumanWakeSelection.select(DECISION, second, otherGroup, SNAPSHOT, message).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> HumanWakeSelection.select(DECISION, first, otherGroup, SNAPSHOT, message));
    }

    @Test void unsupportedFilterFailsClosed() {
        var wait = waitAt("do/1");
        String unsupported = "{\"with\":{\"extension\":\"x\",\"type\":\"" + HumanDecisionWake.TYPE + "\"}}";
        String snapshot = "{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"one\":" + unsupported + "}}}}";
        var group = group(wait, unsupported, snapshot);
        assertThrows(IllegalArgumentException.class,
                () -> HumanWakeSelection.select(DECISION, wait, group, snapshot, HumanDecisionWake.message(DECISION, wait)));
    }

    @Test void eventThatAlsoMatchesUntilCannotBecomeAHumanDataSelection() {
        var wait = waitAt("do/1");
        String snapshot = "{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"any\":["
                + FILTER + "],\"until\":{\"any\":[" + FILTER + "]}}}}}";
        String hash = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.event-filter.v1").text(FILTER).toBytes());
        var group = new InboxListenGroup(wait.task(), InboxListenGroup.Strategy.ANY, List.of(hash), InboxListenGroup.Stop.ANY,
                List.of(hash), null, InboxListenGroup.snapshotFingerprint(snapshot));
        var message = HumanDecisionWake.message(DECISION, wait);
        var raw = new SdkListenMatchSet(group, snapshot).matches(message);
        assertEquals(InboxListenGroup.Disposition.UNTIL, group.classify(raw.slots()).disposition());
        assertTrue(HumanWakeSelection.select(DECISION, wait, group, snapshot, message).isEmpty());
    }
}
