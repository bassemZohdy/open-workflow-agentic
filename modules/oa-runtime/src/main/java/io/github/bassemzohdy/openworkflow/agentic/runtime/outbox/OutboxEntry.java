package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One row of {@code task.outbox}, the event that mirrors one history row (WP-105b).
 *
 * <p>An entry is written with {@link #writeTo} on the same connection and in the same transaction as the change and
 * its history row. Both stores do this; no other path writes outbox rows. If the transaction rolls back, the entry is
 * gone with the change.
 *
 * @param aggregateType    the kind of row that changed
 * @param aggregateId      the id of the changed row (instance id or agent task id)
 * @param aggregateVersion the row version after the change; unique per aggregate
 * @param partitionKey     the workflow instance id; events of one instance are delivered in order
 * @param eventType        for example {@code instance.status_changed}
 * @param payloadJson      the event payload as JSON text
 * @param occurredAt       the time of the change, equal to the history row's time
 */
public record OutboxEntry(
        AggregateType aggregateType,
        String aggregateId,
        long aggregateVersion,
        String partitionKey,
        String eventType,
        String payloadJson,
        Instant occurredAt) {

    /** The aggregates that write outbox rows. The database check constraint lists the same names. */
    public enum AggregateType {
        INSTANCE, AGENT_TASK
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public OutboxEntry {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
    }

    /**
     * The entry for one change. The event type says what kind of change it was: {@code .created} for version 0,
     * {@code .updated} when the status stayed the same (for example an engine id was recorded), and
     * {@code .status_changed} otherwise.
     *
     * @param details extra payload fields for the consumers (tenant, owner, definition, ...); may be empty
     */
    public static OutboxEntry transition(AggregateType type, String aggregateId, String partitionKey, long version,
                                         String fromStatus, String toStatus, String reason, Subject actor,
                                         Instant occurredAt, Map<String, ?> details) {
        Objects.requireNonNull(actor, "actor");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.putAll(details);
        payload.put("previousStatus", fromStatus);
        payload.put("status", toStatus);
        payload.put("version", version);
        payload.put("reason", reason);
        payload.put("actor", Map.of("kind", actor.kind().name(), "id", actor.id()));
        payload.put("occurredAt", occurredAt.toString());
        String kind = type == AggregateType.INSTANCE ? "instance" : "agent_task";
        String suffix = version == 0 ? "created" : Objects.equals(fromStatus, toStatus) ? "updated" : "status_changed";
        try {
            return new OutboxEntry(type, aggregateId, version, partitionKey, kind + "." + suffix,
                    JSON.writeValueAsString(payload), occurredAt);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload is not representable as JSON", e);
        }
    }

    /** Inserts this entry. Must be called on the connection of the transaction that writes the change. */
    public void writeTo(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into task.outbox (aggregate_type, aggregate_id, aggregate_version, partition_key, event_type,
                    payload, occurred_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """)) {
            insert.setString(1, aggregateType.name());
            insert.setString(2, aggregateId);
            insert.setLong(3, aggregateVersion);
            insert.setString(4, partitionKey);
            insert.setString(5, eventType);
            insert.setString(6, payloadJson);
            insert.setTimestamp(7, Timestamp.from(occurredAt));
            insert.executeUpdate();
        }
    }
}
