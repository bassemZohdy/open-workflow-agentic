package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceToolCallTest {
    private static final UUID WORKER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static AllocatedResourceTask allocation() { return ResourceModelCallTest.task(); }
    private static ResourceExecutionEntry entry(long epoch) { return new ResourceExecutionEntry(allocation().allocation().scope(), epoch); }
    private static ApprovedToolProfile profile(String version) {
        return new ApprovedToolProfile("tools", version, "e".repeat(64), allocation().allocation().modelProfile().agent(),
                Set.of("tool:a"), 3, Duration.ofSeconds(2));
    }
    private static ToolCall request(String arguments) {
        return new ToolCall(allocation().allocation().contract().id(), "tool:a", arguments);
    }
    private static ResourceToolCall call(ResourceExecutionEntry entry, UUID worker, String occurrence, ToolCall request,
                                         ApprovedToolProfile profile) {
        return ResourceToolCall.of(allocation(), profile, entry, worker, occurrence, request);
    }

    @Test void effectKeySurvivesRetriesWorkersAndEpochsButNotNewOccurrences() {
        var first = call(entry(1), WORKER, "graph/do/loop/1", request("{\"x\":1}"), profile("1"));
        var retry = call(entry(2), UUID.randomUUID(), first.nodeOccurrence(), request("{\"x\":1}"), profile("1"));
        assertEquals(first.key(), retry.key()); assertNotEquals(first.fingerprint(), retry.fingerprint());
        assertEquals(first.effectCommand("registered-target").key(), retry.effectCommand("registered-target").key());
        assertEquals(first.effectCommand("registered-target").requestFingerprint(), retry.effectCommand("registered-target").requestFingerprint());
        var next = call(entry(1), WORKER, "graph/do/loop/2", request("{\"x\":1}"), profile("1"));
        assertNotEquals(first.key(), next.key());
        assertNotEquals(first.effectCommand("registered-target").key(), next.effectCommand("registered-target").key());
    }

    @Test void changedArgumentsProfileOrTargetConflictAtTheSameEffectKey() {
        var first = call(entry(1), WORKER, "graph/tool", request("{\"x\":1}"), profile("1"));
        var changed = call(entry(1), WORKER, "graph/tool", request("{\"x\":2}"), profile("1"));
        var reprofiled = call(entry(1), WORKER, "graph/tool", request("{\"x\":1}"), profile("2"));
        var originalEffect = first.effectCommand("registered-target");
        assertEquals(originalEffect.key(), changed.effectCommand("registered-target").key());
        assertNotEquals(originalEffect.requestFingerprint(), changed.effectCommand("registered-target").requestFingerprint());
        assertEquals(originalEffect.key(), reprofiled.effectCommand("registered-target").key());
        assertNotEquals(originalEffect.requestFingerprint(), reprofiled.effectCommand("registered-target").requestFingerprint());
        assertEquals(originalEffect.key(), first.effectCommand("different-target").key());
        assertNotEquals(originalEffect.target(), first.effectCommand("different-target").target());
        assertFalse(originalEffect.toString().contains("\"x\""));
    }

    @Test void rejectsCallsOutsideTheAllocatedTaskOrApprovedProfile() {
        assertThrows(IllegalArgumentException.class, () -> call(entry(1), WORKER, "node",
                new ToolCall(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"), "tool:a", "{}"), profile("1")));
        assertThrows(IllegalArgumentException.class, () -> call(entry(1), WORKER, "node",
                new ToolCall(allocation().allocation().contract().id(), "tool:z", "{}"), profile("1")));
        var wrongAgent = new ApprovedToolProfile("tools", "1", "e".repeat(64), new Subject(SubjectKind.AGENT, "other"),
                Set.of("tool:a"), 3, Duration.ofSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> call(entry(1), WORKER, "node", request("{}"), wrongAgent));
        assertThrows(IllegalArgumentException.class, () -> ResourceToolCall.requestFingerprint(request("\ud800"), profile("1")));
        assertThrows(IllegalArgumentException.class, () -> call(entry(1), WORKER, "\ud800", request("{}"), profile("1")));
    }
}
