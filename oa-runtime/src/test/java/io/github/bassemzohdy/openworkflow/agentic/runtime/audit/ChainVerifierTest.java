package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tamper detection (WP-111 deliverable 3). Each test disables the append-only triggers (as an owner could), changes
 * the audit tables, and checks that the verifier names the first broken sequence.
 */
class ChainVerifierTest {

    private final AuditTrail trail = new AuditTrail(AuditTestDatabase.dataSource());
    private final ChainVerifier verifier = new ChainVerifier(AuditTestDatabase.dataSource());

    @Test
    void anIntactChainVerifies() {
        String chain = chainOfFive();
        ChainReport report = verifier.verify(chain);
        assertTrue(report.intact(), report.reason());
        assertEquals(5, report.eventCount());
        assertEquals(null, report.firstBrokenSequence());
    }

    @Test
    void anUnknownChainIsIntactAndEmpty() {
        ChainReport report = verifier.verify(AuditTestDatabase.newChain());
        assertTrue(report.intact());
        assertEquals(0, report.eventCount());
    }

    @Test
    void anAlteredRowIsCaughtAtItsSequence() throws SQLException {
        String chain = chainOfFive();
        Tamper.run("update audit.event set resource_id = 'forged' where chain_id = '" + chain + "' and seq = 3");

        ChainReport report = verifier.verify(chain);
        assertFalse(report.intact());
        assertEquals(3L, report.firstBrokenSequence());
        assertNotNull(report.firstBrokenEventId());
        assertTrue(report.reason().contains("hash does not match"), report.reason());
    }

    @Test
    void anAlteredVersionStampIsCaught() throws SQLException {
        String chain = AuditTestDatabase.newChain();
        trail.append(AuditDraft.of(AuditTestDatabase.newId(), Instant.now(), AuditTestDatabase.actor(),
                "policy.decided", "tool", "t1", AuditTestDatabase.attributes(chain,
                        Map.of("stamp.policy-set", "2026-10-01"))));
        trail.append(AuditTrailTest.draft(chain, "tool.call.allowed"));

        Tamper.run("update audit.event set attributes = jsonb_set(attributes, '{stamp.policy-set}', '\"2026-09-01\"') "
                + "where chain_id = '" + chain + "' and seq = 1");

        ChainReport report = verifier.verify(chain);
        assertFalse(report.intact());
        assertEquals(1L, report.firstBrokenSequence());
    }

    @Test
    void aDeletedMiddleRowIsCaught() throws SQLException {
        String chain = chainOfFive();
        Tamper.run("delete from audit.event where chain_id = '" + chain + "' and seq = 3");

        ChainReport report = verifier.verify(chain);
        assertFalse(report.intact());
        assertEquals(3L, report.firstBrokenSequence());
        assertEquals(4, report.eventCount());
    }

    @Test
    void aDeletedTailRowIsCaughtByTheRecordedHead() throws SQLException {
        String chain = chainOfFive();
        Tamper.run("delete from audit.event where chain_id = '" + chain + "' and seq = 5");

        ChainReport report = verifier.verify(chain);
        assertFalse(report.intact());
        assertEquals(5L, report.firstBrokenSequence());
        assertTrue(report.reason().contains("does not match the recorded head"), report.reason());
    }

    @Test
    void aReorderedPairIsCaught() throws SQLException {
        String chain = chainOfFive();
        Tamper.run(
                "update audit.event set seq = 100 where chain_id = '" + chain + "' and seq = 2",
                "update audit.event set seq = 2 where chain_id = '" + chain + "' and seq = 3",
                "update audit.event set seq = 3 where chain_id = '" + chain + "' and seq = 100");

        ChainReport report = verifier.verify(chain);
        assertFalse(report.intact());
        assertEquals(2L, report.firstBrokenSequence());
    }

    @Test
    void anInsertedRowWithAValidHashButNoHeadRecordIsCaught() throws SQLException {
        String chain = chainOfFive();
        AuditEvent last = AuditTrailTest.readChain(chain).get(4);
        AuditEvent forged = AuditHash.seal(new AuditEvent(AuditTestDatabase.newId(), 6,
                Instant.parse("2026-10-18T10:00:00.000001Z"), AuditTestDatabase.actor(), "forged", "run", "r-1",
                Map.of(AuditKeys.CHAIN, chain), last.hash(), AuditHash.GENESIS));
        Tamper.run("insert into audit.event (event_id, attributes, seq, occurred_at, actor_kind, actor_id, event_type, "
                + "resource_type, resource_id, previous_hash, hash) values ('" + forged.id().value()
                + "', '{\"oa.chain\": \"" + chain + "\"}', 6, '2026-10-18T10:00:00.000001Z', 'SERVICE', 'audit-test', "
                + "'forged', 'run', 'r-1', '" + forged.previousHash() + "', '" + forged.hash() + "')");

        ChainReport report = verifier.verify(chain);
        assertFalse(report.intact());
        assertEquals(6L, report.firstBrokenSequence());
        assertTrue(report.reason().contains("does not match the recorded head"), report.reason());
    }

    @Test
    void anAlteredHeadRecordIsCaught() throws SQLException {
        String chain = chainOfFive();
        Tamper.run("update audit.head_log set hash = repeat('b', 64) where chain_id = '" + chain + "' and seq = 5");

        ChainReport report = verifier.verify(chain);
        assertFalse(report.intact());
        assertEquals(5L, report.firstBrokenSequence());
        assertTrue(report.reason().contains("recorded head"), report.reason());
    }

    @Test
    void verifyAllCoversEveryChainAndReportsEachSeparately() throws SQLException {
        String good = chainOfFive();
        String bad = chainOfFive();
        Tamper.run("update audit.event set resource_id = 'forged' where chain_id = '" + bad + "' and seq = 1");

        List<ChainReport> reports = verifier.verifyAll();
        assertTrue(reports.stream().anyMatch(r -> r.chainId().equals(good) && r.intact()));
        assertTrue(reports.stream().anyMatch(r -> r.chainId().equals(bad) && !r.intact()));
    }

    private String chainOfFive() {
        String chain = AuditTestDatabase.newChain();
        for (int i = 1; i <= 5; i++) {
            trail.append(AuditTrailTest.draft(chain, "step." + i));
        }
        return chain;
    }
}
