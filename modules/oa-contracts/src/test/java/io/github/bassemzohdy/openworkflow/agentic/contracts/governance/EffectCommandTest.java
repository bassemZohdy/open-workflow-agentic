package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import org.junit.jupiter.api.Test;

class EffectCommandTest {
    @Test
    void logicalOccurrenceIsStableButOtherOccurrencesAndScopesAreDistinct() {
        ThreadId thread = ThreadId.derive("tenant", "alice", "run");
        EffectCommand first = EffectCommand.of(thread, "charge", "line:1", "payments", "amount=10");
        assertEquals(first, EffectCommand.of(thread, "charge", "line:1", "payments", "amount=10"));
        assertNotEquals(first.key(), EffectCommand.of(thread, "charge", "line:2", "payments", "amount=10").key());
        assertNotEquals(first.key(), EffectCommand.of(ThreadId.derive("tenant", "bob", "run"),
                "charge", "line:1", "payments", "amount=10").key());
        EffectCommand changed = EffectCommand.of(thread, "charge", "line:1", "payments", "amount=20");
        assertEquals(first.key(), changed.key(), "payload changes must conflict, not silently create a new effect");
        assertNotEquals(first.requestFingerprint(), changed.requestFingerprint());
    }

    @Test
    void lengthPrefixedEncodingAvoidsDelimiterAmbiguityAndRejectsInvalidText() {
        ThreadId thread = ThreadId.derive("tenant", "alice", "run");
        assertNotEquals(EffectCommand.of(thread, "a:b", "c", "t", "x").key(),
                EffectCommand.of(thread, "a", "b:c", "t", "x").key());
        assertThrows(IllegalArgumentException.class, () -> EffectCommand.of(thread, "n", " ", "t", "x"));
        assertThrows(IllegalArgumentException.class, () -> EffectCommand.of(thread, "n", "1", "t", "\uD800"));
    }
}
