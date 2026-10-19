package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Applies outbox events to {@code task.task_view} (WP-105b). The projection is a read model: it is never read to
 * decide anything, and {@link TaskViewRebuilder} recreates it from the registry and history.
 *
 * <p><b>Idempotent.</b> The relay delivers the events of one instance in outbox order (see {@link OutboxRelay}). Each
 * view row stores the outbox id of the last event applied to it. An event whose id is not greater than that is a
 * duplicate or an old event, and it changes nothing. The update checks the stored id in the same statement, so two
 * deliveries of one event cannot both apply.
 *
 * <p><b>Out of order.</b> An agent task event for an instance with no view row is refused with an exception. The
 * relay then retries it with backoff. In practice the instance's creation event always comes first.
 */
@ApplicationScoped
public class TaskViewProjector implements OutboxConsumer {

    public static final String NAME = "task-view-projector";

    private static final String INSERT_INSTANCE = """
            insert into task.task_view (instance_id, tenant, owner_kind, owner_id, definition_id, definition_version,
                instance_status, instance_version, agent_task_id, agent_task_status, agent_outbox_id,
                last_reason, last_transition_at, last_outbox_id, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, null, null, null, ?, ?, ?, ?)
            on conflict (instance_id) do nothing
            """;

    private static final String UPDATE_INSTANCE = """
            update task.task_view
               set instance_status = ?, instance_version = ?,
                   last_reason = ?, last_transition_at = ?, last_outbox_id = ?, updated_at = ?
             where instance_id = ? and last_outbox_id < ?
            """;

    private static final String UPDATE_AGENT_TASK = """
            update task.task_view
               set agent_task_id = ?, agent_task_status = ?, agent_outbox_id = ?,
                   last_reason = ?, last_transition_at = ?, last_outbox_id = ?, updated_at = ?
             where instance_id = ? and last_outbox_id < ?
            """;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void apply(Connection connection, OutboxEvent event) throws SQLException {
        switch (event.aggregateType()) {
            case INSTANCE -> applyInstance(connection, event);
            case AGENT_TASK -> applyAgentTask(connection, event);
        }
    }

    private void applyInstance(Connection connection, OutboxEvent event) throws SQLException {
        JsonNode p = event.payload();
        Timestamp at = Timestamp.from(event.occurredAt());
        if (event.aggregateVersion() == 0) {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_INSTANCE)) {
                insert.setString(1, event.aggregateId());
                insert.setString(2, text(p, "tenant"));
                insert.setString(3, text(p.path("owner"), "kind"));
                insert.setString(4, text(p.path("owner"), "id"));
                insert.setString(5, text(p, "definitionId"));
                insert.setString(6, text(p, "definitionVersion"));
                insert.setString(7, text(p, "status"));
                insert.setLong(8, 0L);
                insert.setString(9, text(p, "reason"));
                insert.setTimestamp(10, at);
                insert.setLong(11, event.id());
                insert.setTimestamp(12, at);
                insert.executeUpdate();
            }
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(UPDATE_INSTANCE)) {
            update.setString(1, text(p, "status"));
            update.setLong(2, event.aggregateVersion());
            update.setString(3, text(p, "reason"));
            update.setTimestamp(4, at);
            update.setLong(5, event.id());
            update.setTimestamp(6, at);
            update.setString(7, event.partitionKey());
            update.setLong(8, event.id());
            if (update.executeUpdate() == 0) {
                requireDuplicateOrRefuse(connection, event);
            }
        }
    }

    private void applyAgentTask(Connection connection, OutboxEvent event) throws SQLException {
        JsonNode p = event.payload();
        Timestamp at = Timestamp.from(event.occurredAt());
        try (PreparedStatement update = connection.prepareStatement(UPDATE_AGENT_TASK)) {
            update.setString(1, event.aggregateId());
            update.setString(2, text(p, "status"));
            update.setLong(3, event.id());
            update.setString(4, text(p, "reason"));
            update.setTimestamp(5, at);
            update.setLong(6, event.id());
            update.setTimestamp(7, at);
            update.setString(8, event.partitionKey());
            update.setLong(9, event.id());
            if (update.executeUpdate() == 0) {
                requireDuplicateOrRefuse(connection, event);
            }
        }
    }

    /** No row changed: either the event was already applied (or is older), or the view row does not exist yet. */
    private static void requireDuplicateOrRefuse(Connection connection, OutboxEvent event) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement(
                "select 1 from task.task_view where instance_id = ?")) {
            exists.setString(1, event.partitionKey());
            try (ResultSet rs = exists.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("instance " + event.partitionKey()
                            + " is not in the view yet; event " + event.id() + " waits for it");
                }
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("outbox payload lacks " + field);
        }
        return value.asText();
    }
}
