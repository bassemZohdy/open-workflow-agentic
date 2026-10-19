package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ChainVerification;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Verifies the audit chains (M15). Reads one chain in sequence order, in a single repeatable-read snapshot together
 * with its head record, and checks: sequence continuity, previous-hash links, each event's hash (through
 * {@link AuditHash}), the number of events against the head, and the last hash against the head.
 *
 * <p>What it detects: a changed row, a deleted row (middle or tail), a reordered row, an inserted row, a changed or
 * removed head record. What it cannot detect: someone with owner rights who rewrites the events and the head record
 * together from some point. Only an external anchor of the head detects that (docs/audit.md, gap).
 */
public final class ChainVerifier {

    private final DataSource dataSource;

    public ChainVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Verifies one chain. An unknown chain is intact with zero events. */
    public ChainReport verify(String chainId) {
        AuditKeys.requireChainId(chainId);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                List<AuditEvent> events = readEvents(connection, chainId);
                Optional<HeadRecord> head = readHead(connection, chainId);
                connection.commit();
                return evaluate(chainId, events, head);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new AuditStoreException("chain " + chainId + " could not be read for verification", e);
        }
    }

    /** The ids of all chains that have events, sorted. */
    public List<String> chainIds() {
        List<String> chains = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select distinct chain_id from audit.event order by chain_id");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                chains.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new AuditStoreException("chains could not be listed", e);
        }
        return chains;
    }

    /** Verifies every chain. Used by the scheduled verification job and by {@code POST /audit/verify}. */
    public List<ChainReport> verifyAll() {
        return chainIds().stream().map(this::verify).toList();
    }

    /**
     * The verification rules on data already read. Package-private so that the rules can be read in one place; the
     * database reads are in {@link #verify(String)}.
     */
    static ChainReport evaluate(String chainId, List<AuditEvent> events, Optional<HeadRecord> head) {
        ChainVerification links = AuditHash.verifyChain(events);
        if (!links.valid()) {
            return brokenAt(chainId, events, links.firstInvalidIndex(), links.reason());
        }
        if (head.isEmpty()) {
            if (events.isEmpty()) {
                return ChainReport.intact(chainId, 0);
            }
            return brokenAt(chainId, events, 0, "chain has events but no head record");
        }
        HeadRecord recorded = head.get();
        if (events.size() != recorded.sequence()) {
            int index = (int) Math.min(recorded.sequence(), events.size());
            return brokenAt(chainId, events, index, "event count " + events.size()
                    + " does not match the recorded head at sequence " + recorded.sequence());
        }
        if (events.isEmpty()) {
            return ChainReport.intact(chainId, 0);
        }
        String last = events.get(events.size() - 1).hash();
        if (!last.equals(recorded.hash())) {
            return brokenAt(chainId, events, events.size() - 1, "last hash does not match the recorded head");
        }
        return ChainReport.intact(chainId, events.size());
    }

    private static ChainReport brokenAt(String chainId, List<AuditEvent> events, int index, String reason) {
        String eventId = index < events.size() ? events.get(index).id().value() : null;
        return ChainReport.broken(chainId, events.size(), index + 1L, eventId, reason);
    }

    private static List<AuditEvent> readEvents(Connection connection, String chainId) throws SQLException {
        List<AuditEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("select " + AuditRows.EVENT_COLUMNS
                + " from audit.event where chain_id = ? order by seq")) {
            statement.setString(1, chainId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(AuditRows.event(rs));
                }
            }
        }
        return events;
    }

    private static Optional<HeadRecord> readHead(Connection connection, String chainId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select seq, hash from audit.head_log where chain_id = ? order by seq desc limit 1")) {
            statement.setString(1, chainId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new HeadRecord(rs.getLong("seq"), rs.getString("hash")));
            }
        }
    }

    /** The last record of the head log: the sequence and hash the chain must end at. */
    record HeadRecord(long sequence, String hash) {
    }
}
