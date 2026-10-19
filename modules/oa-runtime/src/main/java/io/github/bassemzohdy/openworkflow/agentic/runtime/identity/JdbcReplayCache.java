package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * The replay cache in PostgreSQL ({@code identity.token_use}). One statement counts and decides, so concurrent uses are
 * serialized by the row lock. Rows whose token expired more than a minute ago are deleted, at most once a minute per
 * process. A database failure is an {@link IllegalStateException}, which the guard turns into a refusal.
 */
public final class JdbcReplayCache implements ReplayCache {

    private static final Duration PURGE_EVERY = Duration.ofMinutes(1);
    private static final Duration KEEP_AFTER_EXPIRY = Duration.ofMinutes(1);

    private final DataSource dataSource;
    private final AtomicLong nextPurgeMillis = new AtomicLong();

    public JdbcReplayCache(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean use(String key, Instant expiresAt, int maxUses, Instant now) {
        if (maxUses < 1) {
            throw new IllegalArgumentException("maxUses must be at least 1");
        }
        try (Connection c = dataSource.getConnection()) {
            purge(c, now);
            // The insert counts the first use. The conflict branch counts a further use only while below the limit;
            // when the limit is reached no row is returned, which is the refusal.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO identity.token_use (token_key, uses, expires_at) VALUES (?, 1, ?) "
                            + "ON CONFLICT (token_key) DO UPDATE SET uses = identity.token_use.uses + 1 "
                            + "WHERE identity.token_use.uses < ? RETURNING uses")) {
                ps.setString(1, key);
                ps.setTimestamp(2, Timestamp.from(expiresAt));
                ps.setInt(3, maxUses);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) <= maxUses;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the replay cache is unavailable", e);
        }
    }

    private void purge(Connection c, Instant now) throws SQLException {
        long nowMillis = now.toEpochMilli();
        long due = nextPurgeMillis.get();
        if (nowMillis < due || !nextPurgeMillis.compareAndSet(due, nowMillis + PURGE_EVERY.toMillis())) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM identity.token_use WHERE expires_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(now.minus(KEEP_AFTER_EXPIRY)));
            ps.executeUpdate();
        }
    }
}
