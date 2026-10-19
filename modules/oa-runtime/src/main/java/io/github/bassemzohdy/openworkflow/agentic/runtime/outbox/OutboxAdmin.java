package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Lag and poison rows of the outbox (WP-105b).
 *
 * <p>A failed row is never dropped. An operator lists it with {@link #failures} (the class name of the last error is
 * there, not its message), fixes the cause, and calls {@link #requeue}. The row then gets its attempts back and is
 * delivered again, so the consumers see it at least once more, which their idempotency allows. The operations API
 * (WP-105d) will expose these calls; until then they are called from code.
 */
@ApplicationScoped
public class OutboxAdmin {

    /**
     * @param unpublished                 rows not yet published, failed ones included
     * @param failed                      rows marked failed (poison)
     * @param oldestUnpublishedAgeSeconds age of the oldest unpublished row, 0 when there is none
     */
    public record Lag(long unpublished, long failed, double oldestUnpublishedAgeSeconds) {
    }

    /** A failed row, for an operator. */
    public record Failure(long id, String aggregateType, String aggregateId, long aggregateVersion, String eventType,
                          int attempts, String lastError, Instant failedAt) {
    }

    private final DataSource dataSource;

    @Inject
    public OutboxAdmin(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Lag lag() {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    select count(*) as unpublished,
                           count(*) filter (where failed_at is not null) as failed,
                           coalesce(extract(epoch from now() - min(occurred_at))::float8, 0) as oldest
                      from task.outbox
                     where published_at is null
                    """);
                 ResultSet rs = select.executeQuery()) {
                rs.next();
                return new Lag(rs.getLong("unpublished"), rs.getLong("failed"), rs.getDouble("oldest"));
            }
        });
    }

    /** The failed rows, oldest first. */
    public List<Failure> failures(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Transactions.inTransaction(dataSource, connection -> {
            List<Failure> failures = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    select id, aggregate_type, aggregate_id, aggregate_version, event_type, attempts, last_error, failed_at
                      from task.outbox
                     where failed_at is not null
                     order by id
                     limit ?
                    """)) {
                select.setInt(1, limit);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        failures.add(new Failure(rs.getLong("id"), rs.getString("aggregate_type"),
                                rs.getString("aggregate_id"), rs.getLong("aggregate_version"),
                                rs.getString("event_type"), rs.getInt("attempts"), rs.getString("last_error"),
                                rs.getTimestamp("failed_at").toInstant()));
                    }
                }
            }
            return failures;
        });
    }

    /**
     * Puts a failed row back in the queue. Its attempts start again at zero.
     *
     * @return true when the row was failed and is now queued; false when it was not failed
     */
    public boolean requeue(long id) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    update task.outbox
                       set failed_at = null, attempts = 0, last_error = null, next_attempt_at = now()
                     where id = ? and failed_at is not null and published_at is null
                    """)) {
                update.setLong(1, id);
                return update.executeUpdate() == 1;
            }
        });
    }
}
