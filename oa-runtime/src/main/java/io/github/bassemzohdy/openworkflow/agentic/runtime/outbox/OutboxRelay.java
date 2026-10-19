package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Delivers outbox rows to consumers (WP-105b). Any number of relays may run at once, in one process or many.
 *
 * <p><b>Claim.</b> A batch is claimed in one transaction with {@code FOR UPDATE SKIP LOCKED}, in id order. A row is
 * claimable only when no earlier row of its partition (the workflow instance) is unpublished. The check reads
 * committed rows, so an earlier row that another relay holds, or one in backoff or failed, blocks the later rows of
 * that partition. Rows of other partitions are not blocked. Two relays therefore never deliver two rows of one
 * instance at once, and never deliver a later row of an instance before an earlier one.
 *
 * <p><b>Mark.</b> A row is marked published in the transaction that the consumers wrote in. A consumer that throws
 * has its writes for that row rolled back to a savepoint; the row then gets a failed attempt, a backoff, and after
 * {@link RelaySettings#maxAttempts()} attempts a {@code failed_at} stamp. A failed row stays in the table and keeps
 * blocking its partition until {@link OutboxAdmin#requeue} clears it.
 *
 * <p>The relay is not scheduled here; {@link OutboxRelayService} runs it, and tests call {@link #drainOnce} directly.
 */
public final class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ERROR_LENGTH = 256;

    private static final String CLAIM = """
            select o.id, o.aggregate_type, o.aggregate_id, o.aggregate_version, o.partition_key, o.event_type,
                   o.payload::text as payload_text, o.occurred_at, o.attempts
              from task.outbox o
             where o.published_at is null
               and o.failed_at is null
               and o.next_attempt_at <= now()
               and not exists (
                   select 1 from task.outbox earlier
                    where earlier.partition_key = o.partition_key
                      and earlier.id < o.id
                      and earlier.published_at is null)
             order by o.id
             limit ?
               for update of o skip locked
            """;

    private final DataSource dataSource;
    private final List<OutboxConsumer> consumers;
    private final RelaySettings settings;

    public OutboxRelay(DataSource dataSource, List<OutboxConsumer> consumers, RelaySettings settings) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.consumers = List.copyOf(Objects.requireNonNull(consumers, "consumers"));
        if (this.consumers.isEmpty()) {
            throw new IllegalArgumentException("a relay needs at least one consumer");
        }
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Claims one batch and delivers it, in one transaction.
     *
     * @return the number of rows delivered and marked published; failed attempts are not counted
     */
    public int drainOnce() {
        return Transactions.inTransaction(dataSource, this::processBatch).delivered();
    }

    /**
     * Runs batches until one claims nothing, or {@code maxBatches} have run. A batch that only fails still counts as
     * claimed, so a poison row runs out of attempts instead of stopping the drain early.
     *
     * @return the total number of rows delivered
     */
    public int drainAll(int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            Batch batch = Transactions.inTransaction(dataSource, this::processBatch);
            total += batch.delivered();
            if (batch.claimed() == 0) {
                break;
            }
        }
        return total;
    }

    /** The result of one batch: rows claimed and rows delivered. */
    private record Batch(int claimed, int delivered) {
    }

    private Batch processBatch(Connection connection) throws SQLException {
        List<OutboxEvent> batch = claim(connection);
        int published = 0;
        for (OutboxEvent event : batch) {
            Savepoint savepoint = connection.setSavepoint();
            try {
                for (OutboxConsumer consumer : consumers) {
                    consumer.apply(connection, event);
                }
                markPublished(connection, event.id());
                connection.releaseSavepoint(savepoint);
                published++;
            } catch (SQLException | RuntimeException e) {
                connection.rollback(savepoint);
                recordFailure(connection, event, e);
            }
        }
        return new Batch(batch.size(), published);
    }

    private List<OutboxEvent> claim(Connection connection) throws SQLException {
        List<OutboxEvent> batch = new ArrayList<>();
        try (PreparedStatement claim = connection.prepareStatement(CLAIM)) {
            claim.setInt(1, settings.batchSize());
            try (ResultSet rs = claim.executeQuery()) {
                while (rs.next()) {
                    batch.add(new OutboxEvent(
                            rs.getLong("id"),
                            OutboxEntry.AggregateType.valueOf(rs.getString("aggregate_type")),
                            rs.getString("aggregate_id"),
                            rs.getLong("aggregate_version"),
                            rs.getString("partition_key"),
                            rs.getString("event_type"),
                            parse(rs.getString("payload_text")),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getInt("attempts")));
                }
            }
        }
        return batch;
    }

    private static void markPublished(Connection connection, long id) throws SQLException {
        try (PreparedStatement mark = connection.prepareStatement(
                "update task.outbox set published_at = now() where id = ? and published_at is null")) {
            mark.setLong(1, id);
            if (mark.executeUpdate() != 1) {
                throw new SQLException("outbox row " + id + " was not unpublished when it was marked");
            }
        }
    }

    private void recordFailure(Connection connection, OutboxEvent event, Exception cause) throws SQLException {
        int attempts = event.attempts() + 1;
        boolean failed = attempts >= settings.maxAttempts();
        String error = cause.getClass().getName();
        if (error.length() > ERROR_LENGTH) {
            error = error.substring(error.length() - ERROR_LENGTH);
        }
        LOG.warnf("outbox row %d (%s, consumers %s) failed attempt %d: %s%s", event.id(), event.eventType(),
                consumerNames(), attempts, error, failed ? " -- marked failed" : "");
        try (PreparedStatement update = connection.prepareStatement("""
                update task.outbox
                   set attempts = ?,
                       last_error = ?,
                       next_attempt_at = now() + (? * interval '1 millisecond'),
                       failed_at = case when ? then now() else null end
                 where id = ? and published_at is null
                """)) {
            update.setInt(1, attempts);
            update.setString(2, error);
            update.setLong(3, backoffMillis(attempts));
            update.setBoolean(4, failed);
            update.setLong(5, event.id());
            update.executeUpdate();
        }
    }

    private String consumerNames() {
        return consumers.stream().map(OutboxConsumer::name).toList().toString();
    }

    /** Doubles the base after each failure, capped at the maximum. Zero base means retry at once. */
    long backoffMillis(int attempts) {
        long base = settings.backoffBase().toMillis();
        long max = settings.backoffMax().toMillis();
        long delay = base;
        for (int i = 1; i < attempts && delay < max; i++) {
            delay = delay > max / 2 ? max : delay * 2;
        }
        return Math.min(delay, max);
    }

    private static JsonNode parse(String text) throws SQLException {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new SQLException("outbox payload is not valid JSON", e);
        }
    }

    /** The interval the scheduler waits between runs. */
    public Duration interval() {
        return settings.interval();
    }
}
