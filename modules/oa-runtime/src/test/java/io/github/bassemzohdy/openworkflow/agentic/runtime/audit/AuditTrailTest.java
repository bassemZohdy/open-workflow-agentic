package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ChainVerification;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditTrailTest {

    private final AuditTrail trail = new AuditTrail(AuditTestDatabase.dataSource());

    @Test
    void appendSealsTheEventOnTheChainHead() {
        String chain = AuditTestDatabase.newChain();
        AuditEvent first = trail.append(draft(chain, "step.started"));
        AuditEvent second = trail.append(draft(chain, "step.finished"));

        assertEquals(1, first.sequence());
        assertEquals(AuditHash.GENESIS, first.previousHash());
        assertEquals(2, second.sequence());
        assertEquals(first.hash(), second.previousHash());
        assertEquals(AuditHash.compute(first), first.hash());
    }

    @Test
    void storedChainVerifiesWithTheExistingAuditHash() throws SQLException {
        String chain = AuditTestDatabase.newChain();
        for (int i = 0; i < 5; i++) {
            trail.append(draft(chain, "tool.call.allowed"));
        }
        List<AuditEvent> stored = readChain(chain);
        assertEquals(5, stored.size());
        ChainVerification verification = AuditHash.verifyChain(stored, stored.get(4).hash());
        assertTrue(verification.valid(), verification.reason());
    }

    @Test
    void theCallersChainFieldsAreReplacedByTheTrail() throws SQLException {
        String chain = AuditTestDatabase.newChain();
        AuditEvent withPlaceholders = new AuditEvent(AuditTestDatabase.newId(), 99,
                Instant.parse("2026-10-18T10:00:00Z"), AuditTestDatabase.actor(), "policy.decided", "tool", "t1",
                Map.of(AuditKeys.CHAIN, chain), "f".repeat(64), "e".repeat(64));

        trail.record(withPlaceholders);

        List<AuditEvent> events = readChain(chain);
        assertEquals(1, events.size());
        assertEquals(1, events.get(0).sequence());
        assertEquals(AuditHash.GENESIS, events.get(0).previousHash());
        assertTrue(AuditHash.verifyChain(events).valid());
    }

    @Test
    void occurredAtIsStoredAtMicrosecondPrecisionSoTheHashStillVerifies() throws SQLException {
        String chain = AuditTestDatabase.newChain();
        Instant withNanos = Instant.parse("2026-10-18T10:15:30.123456789Z");
        trail.append(AuditDraft.of(AuditTestDatabase.newId(), withNanos, AuditTestDatabase.actor(), "a", "r", "1",
                Map.of(AuditKeys.CHAIN, chain)));

        List<AuditEvent> events = readChain(chain);
        assertEquals(Instant.parse("2026-10-18T10:15:30.123456Z"), events.get(0).occurredAt());
        assertTrue(AuditHash.verifyChain(events).valid());
    }

    @Test
    void recordIsTheSinkEntryPoint() throws SQLException {
        String chain = AuditTestDatabase.newChain();
        AuditSink sink = trail;
        sink.record(draft(chain, "decision.allowed"));
        assertEquals(1, readChain(chain).size());
    }

    @Test
    void anEventWithoutAChainAttributeGoesToTheDefaultChain() {
        AuditEvent stored = trail.append(AuditDraft.of(AuditTestDatabase.newId(), Instant.now(),
                AuditTestDatabase.actor(), "system.started", "system", "node-1", Map.of()));
        assertTrue(stored.sequence() >= 1);
        assertTrue(stored.attributes().isEmpty());
    }

    @Test
    void anInvalidChainIdIsRefusedBeforeAnyWrite() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> trail.append(draft("bad chain!", "a")));
        assertTrue(readChain("bad chain!").isEmpty());
    }

    static AuditEvent draft(String chain, String action) {
        return AuditDraft.of(AuditTestDatabase.newId(), Instant.now(), AuditTestDatabase.actor(), action, "run", "r-1",
                AuditTestDatabase.attributes(chain, Map.of()));
    }

    static List<AuditEvent> readChain(String chain) throws SQLException {
        List<AuditEvent> events = new ArrayList<>();
        try (var connection = AuditTestDatabase.connection();
             PreparedStatement statement = connection.prepareStatement("select " + AuditRows.EVENT_COLUMNS
                     + " from audit.event where chain_id = ? order by seq")) {
            statement.setString(1, chain);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(AuditRows.event(rs));
                }
            }
        }
        return events;
    }
}
