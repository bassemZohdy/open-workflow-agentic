package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The bindings in PostgreSQL ({@code identity.issued_token_binding}, append-only, owned by the shared migration).
 *
 * <p>{@link #bind} commits on its own connection and transaction. When a JTA transaction manager is given, any ambient
 * caller transaction is suspended around the write, so a caller that rolls back cannot erase a binding whose token was
 * already released, and the caller's unrelated work is never committed by this write. A database failure is an
 * {@link IllegalStateException}.
 */
public final class JdbcIssuedTokenBindings implements IssuedTokenBindings {

    private final DataSource dataSource;
    private final TransactionManager transactions;

    /** For a plain DataSource without JTA (a test or a standalone tool). */
    public JdbcIssuedTokenBindings(DataSource dataSource) {
        this(dataSource, null);
    }

    /** @param transactions suspended around the write so that the caller's transaction cannot undo it; may be null */
    public JdbcIssuedTokenBindings(DataSource dataSource, TransactionManager transactions) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactions = transactions;
    }

    @Override
    public void bind(String issuer, String tokenId, Binding binding) {
        Transaction suspended = suspend();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO identity.issued_token_binding (issuer, token_id, grant_id, tenant, expires_at) "
                                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (issuer, token_id) DO NOTHING")) {
                    ps.setString(1, issuer);
                    ps.setString(2, tokenId);
                    ps.setString(3, binding.grantId().toString());
                    ps.setString(4, binding.tenant());
                    ps.setTimestamp(5, Timestamp.from(binding.expiresAt()));
                    ps.executeUpdate();
                }
                // a fresh read: a concurrent winner is visible here at READ COMMITTED; every field must be identical
                Optional<Binding> stored = read(c, issuer, tokenId);
                if (stored.isEmpty() || !stored.get().equals(binding)) {
                    c.rollback();
                    throw new IllegalStateException("the token is already bound to a different grant, tenant or expiry");
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the token bindings are unavailable", e);
        } finally {
            resume(suspended);
        }
    }

    @Override
    public Optional<Binding> find(String issuer, String tokenId) {
        try (Connection c = dataSource.getConnection()) {
            return read(c, issuer, tokenId);
        } catch (SQLException e) {
            throw new IllegalStateException("the token bindings are unavailable", e);
        }
    }

    private static Optional<Binding> read(Connection c, String issuer, String tokenId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT grant_id, tenant, expires_at "
                + "FROM identity.issued_token_binding WHERE issuer = ? AND token_id = ?")) {
            ps.setString(1, issuer);
            ps.setString(2, tokenId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Binding(Ulid.parse(rs.getString(1)), rs.getString(2),
                        rs.getTimestamp(3).toInstant())) : Optional.empty();
            }
        }
    }

    private Transaction suspend() {
        if (transactions == null) {
            return null;
        }
        try {
            return transactions.suspend();
        } catch (SystemException e) {
            throw new IllegalStateException("the caller transaction could not be suspended", e);
        }
    }

    private void resume(Transaction suspended) {
        if (suspended == null) {
            return;
        }
        try {
            transactions.resume(suspended);
        } catch (Exception e) {
            throw new IllegalStateException("the caller transaction could not be resumed", e);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the original failure is the one that matters
        }
    }
}
