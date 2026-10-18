package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.EVENT_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.NOW;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditHashTest {

    private static final Ulid ID_1 = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA1");
    private static final Ulid ID_2 = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA2");
    private static final Ulid ID_3 = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA3");

    /** A correct three-event chain. */
    private static List<AuditEvent> chain() {
        AuditEvent e1 = AuditHash.seal(event(1, AuditHash.GENESIS, ID_1, "tool.call.allowed"));
        AuditEvent e2 = AuditHash.seal(event(2, e1.hash(), ID_2, "effect.intended"));
        AuditEvent e3 = AuditHash.seal(event(3, e2.hash(), ID_3, "effect.confirmed"));
        return List.of(e1, e2, e3);
    }

    private static AuditEvent event(long sequence, String previous, Ulid id, String action) {
        return new AuditEvent(id, sequence, NOW.plusSeconds(sequence), USER, action, "tool", "tool:case-search",
                Map.of("decision", "ALLOW"), previous, AuditHash.GENESIS);
    }

    /** The same event as {@code original} but with a changed field, sealed with a fresh, self-consistent hash. */
    private static AuditEvent resealed(AuditEvent original, String newAction) {
        return AuditHash.seal(new AuditEvent(original.id(), original.sequence(), original.occurredAt(),
                original.actor(), newAction, original.resourceType(), original.resourceId(), original.attributes(),
                original.previousHash(), AuditHash.GENESIS));
    }

    @Test
    void anIntactChainVerifies() {
        assertTrue(AuditHash.verifyChain(chain()).valid());
        assertTrue(AuditHash.verifyChain(chain(), chain().get(2).hash()).valid());
    }

    @Test
    void anEmptyLogIsIntactAndEndsAtGenesis() {
        assertTrue(AuditHash.verifyChain(List.of()).valid());
        assertTrue(AuditHash.verifyChain(List.of(), AuditHash.GENESIS).valid());
    }

    @Test
    void theFirstEventMustLinkToGenesis() {
        AuditEvent bad = AuditHash.seal(event(1, "f".repeat(64), ID_1, "x"));
        ChainVerification v = AuditHash.verifyChain(List.of(bad));
        assertFalse(v.valid());
        assertEquals(0, v.firstInvalidIndex());
    }

    @Test
    void changedContentWithTheOldHashIsDetected() {
        List<AuditEvent> chain = chain();
        AuditEvent original = chain.get(1);
        AuditEvent tampered = new AuditEvent(original.id(), original.sequence(), original.occurredAt(),
                original.actor(), "effect.never-happened", original.resourceType(), original.resourceId(),
                original.attributes(), original.previousHash(), original.hash());
        ChainVerification v = AuditHash.verifyChain(List.of(chain.get(0), tampered, chain.get(2)));
        assertFalse(v.valid());
        assertEquals(1, v.firstInvalidIndex());
        assertEquals("hash does not match the event content", v.reason());
    }

    @Test
    void changedAttributeIsDetected() {
        List<AuditEvent> chain = chain();
        AuditEvent original = chain.get(0);
        AuditEvent tampered = new AuditEvent(original.id(), original.sequence(), original.occurredAt(),
                original.actor(), original.action(), original.resourceType(), original.resourceId(),
                Map.of("decision", "DENY"), original.previousHash(), original.hash());
        assertEquals(0, AuditHash.verifyChain(List.of(tampered, chain.get(1), chain.get(2))).firstInvalidIndex());
    }

    @Test
    void recomputingOneEventDoesNotHideTheChangeFromTheNextLink() {
        List<AuditEvent> chain = chain();
        AuditEvent rewritten = resealed(chain.get(0), "tool.call.denied");
        // The rewritten event is self-consistent, but event 2 still points at the old hash.
        ChainVerification v = AuditHash.verifyChain(List.of(rewritten, chain.get(1), chain.get(2)));
        assertFalse(v.valid());
        assertEquals(1, v.firstInvalidIndex());
        assertEquals("previous hash does not link to the preceding event", v.reason());
    }

    @Test
    void rewritingTheWholeTailIsOnlyDetectedAgainstAnExternalHead() {
        List<AuditEvent> chain = chain();
        AuditEvent e1 = resealed(chain.get(0), "tool.call.denied");
        AuditEvent e2 = AuditHash.seal(new AuditEvent(ID_2, 2, chain.get(1).occurredAt(), USER, "effect.intended",
                "tool", "tool:case-search", Map.of("decision", "ALLOW"), e1.hash(), AuditHash.GENESIS));
        AuditEvent e3 = AuditHash.seal(new AuditEvent(ID_3, 3, chain.get(2).occurredAt(), USER, "effect.confirmed",
                "tool", "tool:case-search", Map.of("decision", "ALLOW"), e2.hash(), AuditHash.GENESIS));
        List<AuditEvent> rewritten = List.of(e1, e2, e3);

        assertTrue(AuditHash.verifyChain(rewritten).valid(), "a full rewrite is self-consistent");
        ChainVerification anchored = AuditHash.verifyChain(rewritten, chain.get(2).hash());
        assertFalse(anchored.valid(), "the externally kept head exposes the rewrite");
    }

    @Test
    void aRemovedMiddleEventIsDetected() {
        List<AuditEvent> chain = chain();
        ChainVerification v = AuditHash.verifyChain(List.of(chain.get(0), chain.get(2)));
        assertFalse(v.valid());
        assertEquals(1, v.firstInvalidIndex());
    }

    @Test
    void aReorderedChainIsDetected() {
        List<AuditEvent> chain = chain();
        ChainVerification v = AuditHash.verifyChain(List.of(chain.get(0), chain.get(2), chain.get(1)));
        assertFalse(v.valid());
        assertEquals(1, v.firstInvalidIndex());
    }

    @Test
    void aTruncatedChainIsOnlyDetectedAgainstTheHead() {
        List<AuditEvent> chain = chain();
        List<AuditEvent> truncated = chain.subList(0, 2);
        assertTrue(AuditHash.verifyChain(truncated).valid(), "a prefix is a valid chain on its own");
        ChainVerification v = AuditHash.verifyChain(truncated, chain.get(2).hash());
        assertFalse(v.valid());
        assertEquals(truncated.size(), v.firstInvalidIndex());
    }

    @Test
    void anInsertedEventWithTheWrongSequenceIsDetected() {
        List<AuditEvent> chain = chain();
        AuditEvent inserted = AuditHash.seal(event(7, chain.get(0).hash(), EVENT_ID, "intruder"));
        ChainVerification v = AuditHash.verifyChain(List.of(chain.get(0), inserted, chain.get(1)));
        assertFalse(v.valid());
        assertEquals(1, v.firstInvalidIndex());
        assertTrue(v.reason().startsWith("sequence is not consecutive"));
    }

    @Test
    void aHashThatDoesNotMatchTheContentIsDetected() {
        List<AuditEvent> chain = chain();
        AuditEvent e1 = chain.get(0);
        String flipped = (e1.hash().charAt(0) == 'a' ? "b" : "a") + e1.hash().substring(1);
        AuditEvent badHash = new AuditEvent(e1.id(), e1.sequence(), e1.occurredAt(), e1.actor(), e1.action(),
                e1.resourceType(), e1.resourceId(), e1.attributes(), e1.previousHash(), flipped);
        ChainVerification v = AuditHash.verifyChain(List.of(badHash, chain.get(1), chain.get(2)));
        assertFalse(v.valid());
        assertEquals(0, v.firstInvalidIndex());
    }

    @Test
    void theHashIgnoresTheStoredHashFieldAndCoversEveryOtherField() {
        AuditEvent e = event(1, AuditHash.GENESIS, ID_1, "a");
        assertEquals(AuditHash.compute(e), AuditHash.compute(AuditHash.seal(e)));
        assertNotEquals(AuditHash.compute(e), AuditHash.compute(event(1, AuditHash.GENESIS, ID_1, "b")));
        assertNotEquals(AuditHash.compute(e), AuditHash.compute(event(1, AuditHash.GENESIS, ID_2, "a")));
        assertNotEquals(AuditHash.compute(e), AuditHash.compute(event(2, AuditHash.GENESIS, ID_1, "a")));
        assertNotEquals(AuditHash.compute(e), AuditHash.compute(event(1, "0".repeat(63) + "1", ID_1, "a")));
    }

    @Test
    void theHashDoesNotDependOnAttributeOrder() {
        AuditEvent a = new AuditEvent(ID_1, 1, NOW, USER, "x", "t", "r",
                Map.of("k1", "v1", "k2", "v2", "k3", "v3"), AuditHash.GENESIS, AuditHash.GENESIS);
        AuditEvent b = new AuditEvent(ID_1, 1, NOW, USER, "x", "t", "r",
                Map.of("k3", "v3", "k1", "v1", "k2", "v2"), AuditHash.GENESIS, AuditHash.GENESIS);
        assertEquals(AuditHash.compute(a), AuditHash.compute(b));
    }

    @Test
    void chainVerificationRequiresAnIndexAndReasonWhenInvalid() {
        assertTrue(ChainVerification.intact().valid());
        assertThrows(IllegalArgumentException.class, () -> new ChainVerification(false, -1, "x"));
        assertThrows(IllegalArgumentException.class, () -> new ChainVerification(false, 0, " "));
        assertThrows(IllegalArgumentException.class, () -> new ChainVerification(true, 0, null));
    }

    @Test
    void auditEventRejectsMalformedFields() {
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(ID_1, 0, NOW, USER, "a", "t", "r", Map.of(),
                AuditHash.GENESIS, AuditHash.GENESIS));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(ID_1, 1, NOW, USER, "a", "t", "r", Map.of(),
                "not-hex", AuditHash.GENESIS));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(ID_1, 1, NOW, USER, "a", "t", "r", Map.of(),
                AuditHash.GENESIS, "A".repeat(64)));
        Map<String, String> tooMany = new java.util.HashMap<>();
        for (int i = 0; i <= AuditEvent.MAX_ATTRIBUTES; i++) {
            tooMany.put("k" + i, "v");
        }
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(ID_1, 1, NOW, USER, "a", "t", "r", tooMany,
                AuditHash.GENESIS, AuditHash.GENESIS));
    }
}
