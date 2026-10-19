package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Appends events to the hash chains in {@code audit.event} (M15, plan section 2).
 *
 * <p><b>Concurrency.</b> One append runs in one transaction. It first takes a transaction-scoped advisory lock on its
 * chain ({@code pg_advisory_xact_lock(hashtextextended(chain, 0))}), reads the head (the last sequence and hash), seals
 * the event on that head, inserts it, inserts the head into the insert-only {@code audit.head_log}, and commits. The
 * lock is held until the commit, so the next append on the same chain sees the new head and no sequence number is
 * skipped or reused. Appends to different chains do not wait for each other unless their lock keys collide. Twenty
 * concurrent appenders to one chain produce a gap-free, correctly linked chain (see {@code AuditConcurrencyTest}).
 *
 * <p>Each call opens its own connection from the data source. Pool size bounds the number of concurrent appends.
 */
public final class AuditTrail implements AuditSink {

    private static final String LOCK = "select pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String HEAD = "select seq, hash from audit.event where chain_id = ? order by seq desc limit 1";
    private static final String INSERT_EVENT = "insert into audit.event (event_id, attributes, seq, occurred_at, "
            + "actor_kind, actor_id, event_type, resource_type, resource_id, previous_hash, hash) "
            + "values (?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_HEAD = "insert into audit.head_log (chain_id, seq, hash) values (?, ?, ?)";

    private final DataSource dataSource;

    public AuditTrail(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void record(AuditEvent event) {
        append(event);
    }

    /**
     * Seals {@code draft} onto the end of its chain and commits it.
     *
     * @return the stored event, with its sequence, previous hash and hash
     * @throws AuditStoreException when the event could not be committed; nothing was recorded
     */
    public AuditEvent append(AuditEvent draft) {
        Objects.requireNonNull(draft, "draft");
        String chain = AuditKeys.chainOf(draft.attributes());
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                AuditEvent stored = appendInTransaction(connection, chain, draft);
                connection.commit();
                return stored;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new AuditStoreException("audit event could not be recorded in chain " + chain, e);
        }
    }

    private static AuditEvent appendInTransaction(Connection connection, String chain, AuditEvent draft)
            throws SQLException {
        lock(connection, chain);
        long previousSequence = 0;
        String previousHash = AuditHash.GENESIS;
        try (PreparedStatement head = connection.prepareStatement(HEAD)) {
            head.setString(1, chain);
            try (ResultSet rs = head.executeQuery()) {
                if (rs.next()) {
                    previousSequence = rs.getLong("seq");
                    previousHash = rs.getString("hash");
                }
            }
        }
        AuditEvent sealed = AuditHash.seal(new AuditEvent(
                draft.id(),
                previousSequence + 1,
                draft.occurredAt().truncatedTo(ChronoUnit.MICROS),
                draft.actor(),
                draft.action(),
                draft.resourceType(),
                draft.resourceId(),
                draft.attributes(),
                previousHash,
                AuditHash.GENESIS));
        insertEvent(connection, chain, sealed);
        insertHead(connection, chain, sealed);
        return sealed;
    }

    private static void lock(Connection connection, String chain) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(LOCK)) {
            lock.setString(1, chain);
            lock.execute();
        }
    }

    private static void insertEvent(Connection connection, String chain, AuditEvent event) throws SQLException {
        Map<String, String> attributes = event.attributes();
        if (!chain.equals(AuditKeys.chainOf(attributes))) {
            throw new IllegalStateException("chain mismatch while appending");
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_EVENT)) {
            insert.setString(1, event.id().value());
            insert.setString(2, AuditRows.toJson(attributes));
            insert.setLong(3, event.sequence());
            insert.setObject(4, AuditRows.offset(event.occurredAt()));
            insert.setString(5, event.actor().kind().name());
            insert.setString(6, event.actor().id());
            insert.setString(7, event.action());
            insert.setString(8, event.resourceType());
            insert.setString(9, event.resourceId());
            insert.setString(10, event.previousHash());
            insert.setString(11, event.hash());
            insert.executeUpdate();
        }
    }

    private static void insertHead(Connection connection, String chain, AuditEvent event) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_HEAD)) {
            insert.setString(1, chain);
            insert.setLong(2, event.sequence());
            insert.setString(3, event.hash());
            insert.executeUpdate();
        }
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
