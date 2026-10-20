package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** The bindings in PostgreSQL ({@code identity.issued_token_binding}, append-only). A database failure is an {@link IllegalStateException}. */
public final class JdbcIssuedTokenBindings implements IssuedTokenBindings {

    private final DataSource dataSource;

    public JdbcIssuedTokenBindings(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void bind(String issuer, String tokenId, Ulid grantId, String tenant, Instant expiresAt) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO identity.issued_token_binding (issuer, token_id, grant_id, tenant, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (issuer, token_id) DO NOTHING")) {
                ps.setString(1, issuer);
                ps.setString(2, tokenId);
                ps.setString(3, grantId.toString());
                ps.setString(4, tenant);
                ps.setTimestamp(5, Timestamp.from(expiresAt));
                ps.executeUpdate();
            }
            // the token may already be bound (a repeated jti): that is only acceptable when it is the same grant
            Optional<Ulid> stored = read(c, issuer, tokenId);
            if (stored.isEmpty() || !stored.get().equals(grantId)) {
                throw new IllegalStateException("the token is already bound to another grant");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the token bindings are unavailable", e);
        }
    }

    @Override
    public Optional<Ulid> grantOf(String issuer, String tokenId) {
        try (Connection c = dataSource.getConnection()) {
            return read(c, issuer, tokenId);
        } catch (SQLException e) {
            throw new IllegalStateException("the token bindings are unavailable", e);
        }
    }

    private static Optional<Ulid> read(Connection c, String issuer, String tokenId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT grant_id FROM identity.issued_token_binding WHERE issuer = ? AND token_id = ?")) {
            ps.setString(1, issuer);
            ps.setString(2, tokenId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Ulid.parse(rs.getString(1))) : Optional.empty();
            }
        }
    }
}
