package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditDraftFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditReceipt;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Central, idempotent append of one retained audit source (WP-108). One call is one transaction that holds, in this
 * order, the transaction-scoped lock of the source key and the lock of the selected chain (the same chain lock
 * {@link AuditTrail} takes), and writes {@code audit.event}, {@code audit.head_log} and {@code audit.source_binding}
 * together. It never calls the committing {@link AuditTrail#append} and inserts a binding afterwards.
 *
 * <ul>
 *   <li><b>New source:</b> the full tuple and the exact draft are verified, the chain is
 *       {@link AuditDraftFingerprint#chainOf}, one next event and head are sealed and the matching binding is inserted
 *       in the same transaction.</li>
 *   <li><b>Exact duplicate</b> (also concurrent): every retained binding, event and head field is read and validated and
 *       the original receipt is returned. No new event, time, id, sequence or head.</li>
 *   <li><b>Conflict</b> (changed content, event id, chain or source tuple; an event id already used by anything else):
 *       {@link AuditSourceConflictException}; nothing is appended.</li>
 *   <li><b>Lost commit reply:</b> a same-source readback decides; if it cannot confirm the event the result is
 *       {@link AuditCommitUncertainException}, never absence.</li>
 *   <li><b>Corrupt history, outage or a missing schema:</b> {@link AuditStoreException}; never "not found" and never
 *       permission.</li>
 * </ul>
 *
 * A returned {@link AuditReceipt} is retained-data equality only, not proof of chain authenticity, current authority
 * or dispatch permission. The schema ({@code audit.source_binding}) is GPT-owned and is not applied by this class.
 */
public final class AuditAppendOnce {

    private static final String SOURCE_LOCK = "select pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String BINDING = "select source_key, schema_version, scope_fingerprint, key_fingerprint, "
            + "transition, source_sequence, content_fingerprint, event_id, chain_id, chain_sequence, hash "
            + "from audit.source_binding where source_key = ?";
    private static final String EVENT = "select " + AuditRows.EVENT_COLUMNS + " from audit.event where event_id = ?";
    private static final String EVENT_EXISTS = "select 1 from audit.event where event_id = ?";
    private static final String HEAD = "select hash from audit.head_log where chain_id = ? and seq = ?";
    private static final String INSERT_BINDING = "insert into audit.source_binding (source_key, schema_version, "
            + "scope_fingerprint, key_fingerprint, transition, source_sequence, content_fingerprint, event_id, "
            + "chain_id, chain_sequence, hash) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    public AuditAppendOnce(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Appends {@code draft} for {@code source} exactly once and returns the receipt of the one stored event.
     *
     * @throws IllegalArgumentException     when the source content is not the draft's fingerprint or the draft time is
     *                                      finer than a microsecond (nothing is rounded into another identity)
     * @throws AuditSourceConflictException when the source or event id is already bound to different data
     * @throws AuditCommitUncertainException when the commit reply was lost and readback could not confirm the event
     * @throws AuditStoreException           for any other store failure, corrupt retained history or a missing schema
     */
    public AuditReceipt appendOnce(AuditSource source, AuditEvent draft) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(draft, "draft");
        String chain = AuditDraftFingerprint.chainOf(draft);
        if (!AuditDraftFingerprint.of(draft).equals(source.contentFingerprint())) {
            throw new IllegalArgumentException("audit source content is not the fingerprint of the draft");
        }
        Connection connection = null;
        boolean commitAttempted = false;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            AuditReceipt receipt = inTransaction(connection, source, draft, chain);
            commitAttempted = true;
            connection.commit();
            return receipt;
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) {
                rollbackQuietly(connection, failure);
            }
            if (commitAttempted && failure instanceof SQLException) {
                return readbackAfterLostReply(source, draft, failure);
            }
            throw translate(failure, chain);
        } finally {
            closeQuietly(connection);
        }
    }

    private AuditReceipt inTransaction(Connection c, AuditSource source, AuditEvent draft, String chain)
            throws SQLException {
        String key = source.key();
        try (PreparedStatement lock = c.prepareStatement(SOURCE_LOCK)) {
            lock.setString(1, "oa.audit.source:" + key);
            lock.execute();
        }
        Optional<Binding> existing = binding(c, key);
        if (existing.isPresent()) {
            return validatedReceipt(c, source, draft, chain, existing.get());
        }
        if (exists(c, EVENT_EXISTS, draft.id().value())) {
            throw new AuditSourceConflictException("audit event id is already retained");
        }
        AuditEvent sealed = AuditTrail.appendInTransaction(c, chain, draft);
        try (PreparedStatement insert = c.prepareStatement(INSERT_BINDING)) {
            insert.setString(1, key);
            insert.setInt(2, source.schemaVersion());
            insert.setString(3, source.scopeFingerprint());
            insert.setString(4, source.keyFingerprint());
            insert.setString(5, source.transition().name());
            insert.setLong(6, source.sequence());
            insert.setString(7, source.contentFingerprint());
            insert.setString(8, sealed.id().value());
            insert.setString(9, chain);
            insert.setLong(10, sealed.sequence());
            insert.setString(11, sealed.hash());
            insert.executeUpdate();
        }
        AuditReceipt receipt = new AuditReceipt(source, sealed.id(), chain, sealed.sequence(), sealed.hash(),
                source.contentFingerprint());
        receipt.requireMatches(source, draft);
        return receipt;
    }

    /** Reads every retained field of an existing binding, its event and its head, and compares them with the request. */
    private AuditReceipt validatedReceipt(Connection c, AuditSource source, AuditEvent draft, String chain,
                                          Binding bound) throws SQLException {
        if (bound.schemaVersion != source.schemaVersion()
                || !bound.scope.equals(source.scopeFingerprint())
                || !bound.keyFingerprint.equals(source.keyFingerprint())
                || !bound.transition.equals(source.transition().name())
                || bound.sequence != source.sequence()
                || !bound.content.equals(source.contentFingerprint())
                || !bound.eventId.equals(draft.id().value())
                || !bound.chain.equals(chain)) {
            throw new AuditSourceConflictException("audit source is already bound to different data");
        }
        AuditEvent stored = null;
        try (PreparedStatement select = c.prepareStatement(EVENT)) {
            select.setString(1, bound.eventId);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    stored = AuditRows.event(rs);
                }
            }
        }
        if (stored == null) {
            throw corrupt("bound audit event is missing");
        }
        String headHash = null;
        try (PreparedStatement select = c.prepareStatement(HEAD)) {
            select.setString(1, bound.chain);
            select.setLong(2, bound.chainSequence);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    headHash = rs.getString(1);
                }
            }
        }
        if (headHash == null || !headHash.equals(bound.hash)
                || stored.sequence() != bound.chainSequence || !stored.hash().equals(bound.hash)
                || !AuditKeys.chainOf(stored.attributes()).equals(bound.chain)
                || !AuditHash.compute(stored).equals(stored.hash())
                || !AuditDraftFingerprint.of(stored).equals(bound.content)) {
            throw corrupt("retained audit source, event and head disagree");
        }
        AuditReceipt receipt = new AuditReceipt(source, stored.id(), bound.chain, bound.chainSequence, bound.hash,
                bound.content);
        receipt.requireMatches(source, draft);
        return receipt;
    }

    /** After a failed commit call: the same source decides whether the event was committed. */
    private AuditReceipt readbackAfterLostReply(AuditSource source, AuditEvent draft, Exception commitFailure) {
        String chain = AuditDraftFingerprint.chainOf(draft);
        try (Connection read = dataSource.getConnection()) {
            read.setReadOnly(true);
            read.setAutoCommit(false);
            try {
                Optional<Binding> bound = binding(read, source.key());
                if (bound.isPresent()) {
                    AuditReceipt receipt = validatedReceipt(read, source, draft, chain, bound.get());
                    commitFailure.addSuppressed(new IllegalStateException("committed event confirmed by readback"));
                    return receipt;
                }
            } finally {
                rollbackQuietly(read, commitFailure);
            }
        } catch (SQLException | RuntimeException readbackFailure) {
            commitFailure.addSuppressed(readbackFailure);
        }
        throw new AuditCommitUncertainException(
                "audit commit reply was lost and readback could not confirm the event; repeat the same source", commitFailure);
    }

    private static Optional<Binding> binding(Connection c, String key) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(BINDING)) {
            select.setString(1, key);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Binding(rs.getString("source_key"), rs.getInt("schema_version"),
                        rs.getString("scope_fingerprint"), rs.getString("key_fingerprint"), rs.getString("transition"),
                        rs.getLong("source_sequence"), rs.getString("content_fingerprint"), rs.getString("event_id"),
                        rs.getString("chain_id"), rs.getLong("chain_sequence"), rs.getString("hash")));
            }
        }
    }

    private static boolean exists(Connection c, String sql, String arg) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(sql)) {
            select.setString(1, arg);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static RuntimeException translate(Exception failure, String chain) {
        if (failure instanceof AuditSourceConflictException conflict) {
            return conflict;
        }
        if (failure instanceof AuditStoreException store) {
            return store;
        }
        if (failure instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
            return new AuditSourceConflictException("audit event id or source is already bound");
        }
        if (failure instanceof IllegalArgumentException invalid) {
            return invalid;
        }
        if (failure instanceof SQLException sql && "42P01".equals(sql.getSQLState())) {
            return new AuditStoreException("audit source binding schema is unavailable", sql);
        }
        return new AuditStoreException("audit source could not be recorded in chain " + chain, failure);
    }

    private static AuditStoreException corrupt(String message) {
        return new AuditStoreException(message, new IllegalStateException(message));
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // a failed close cannot change the outcome that was already decided
            }
        }
    }

    private record Binding(String sourceKey, int schemaVersion, String scope, String keyFingerprint, String transition,
                           long sequence, String content, String eventId, String chain, long chainSequence,
                           String hash) {
    }
}
