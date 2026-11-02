package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApprovedToolProfileTest {
    private static final Subject AGENT = new Subject(SubjectKind.AGENT, "research");
    private static ApprovedToolProfile profile(Set<String> tools, int calls, Duration timeout) {
        return new ApprovedToolProfile("tools", "1", "a".repeat(64), AGENT, tools, calls, timeout);
    }
    @Test void canonicalIdentityPinsEveryBoundAndIgnoresSetIterationOrder() {
        var a = profile(Set.of("alpha", "beta"), 3, Duration.ofSeconds(1));
        assertEquals(a.fingerprint(), profile(Set.of("beta", "alpha"), 3, Duration.ofSeconds(1)).fingerprint());
        assertNotEquals(a.fingerprint(), profile(Set.of("alpha"), 3, Duration.ofSeconds(1)).fingerprint());
        assertNotEquals(a.fingerprint(), profile(Set.of("alpha", "beta"), 4, Duration.ofSeconds(1)).fingerprint());
        assertNotEquals(a.fingerprint(), profile(Set.of("alpha", "beta"), 3, Duration.ofSeconds(2)).fingerprint());
        assertNotEquals(a.fingerprint(), new ApprovedToolProfile("other", "1", "a".repeat(64), AGENT,
                a.tools(), 3, Duration.ofSeconds(1)).fingerprint());
        assertNotEquals(a.fingerprint(), new ApprovedToolProfile("tools", "2", "a".repeat(64), AGENT,
                a.tools(), 3, Duration.ofSeconds(1)).fingerprint());
        assertNotEquals(a.fingerprint(), new ApprovedToolProfile("tools", "1", "b".repeat(64), AGENT,
                a.tools(), 3, Duration.ofSeconds(1)).fingerprint());
        assertNotEquals(a.fingerprint(), new ApprovedToolProfile("tools", "1", "a".repeat(64),
                new Subject(SubjectKind.AGENT, "other"), a.tools(), 3, Duration.ofSeconds(1)).fingerprint());
    }
    @Test void refusesInvalidConfigurationAndOutOfRangeBounds() {
        assertThrows(IllegalArgumentException.class, () -> profile(Set.of("alpha"), 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> profile(Set.of("alpha"), 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> profile(Set.of("alpha"), 1, Duration.ofMinutes(2)));
        assertThrows(IllegalArgumentException.class, () -> new ApprovedToolProfile("p", "1", "x", AGENT,
                Set.of("alpha"), 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ApprovedToolProfile("p", "1", "a".repeat(64),
                new Subject(SubjectKind.USER, "person"), Set.of("alpha"), 1, Duration.ofSeconds(1)));
    }
    @Test void profileCannotExpandTheTaskOrChangeItsAgent() {
        var allocation = ResourceTaskAllocationTest.allocation();
        var task = allocation.contract(); var actor = allocation.modelProfile().agent();
        var allowed = new ApprovedToolProfile("p", "1", "a".repeat(64), actor, task.permittedTools(), 1, Duration.ofSeconds(1));
        assertDoesNotThrow(() -> allowed.requireWithin(task, actor));
        assertThrows(IllegalArgumentException.class, () -> profile(Set.of("unapproved"), 1, Duration.ofSeconds(1))
                .requireWithin(task, actor));
        assertThrows(IllegalArgumentException.class, () -> allowed.requireWithin(task, new Subject(SubjectKind.AGENT, "other")));
    }
}
