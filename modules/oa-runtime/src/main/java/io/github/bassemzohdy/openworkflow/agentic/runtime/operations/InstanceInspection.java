package io.github.bassemzohdy.openworkflow.agentic.runtime.operations;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewQuery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The read side of the inspect operation (WP-105d): the projection row, the outbox state and the repair events of one
 * instance. Read-only; it writes nothing and takes no lock.
 */
@ApplicationScoped
public class InstanceInspection {

    /** The most recent repair events returned by one inspection. */
    static final int MAX_REPAIRS = 100;

    private final DataSource dataSource;
    private final TaskViewQuery views;

    @Inject
    public InstanceInspection(DataSource dataSource, TaskViewQuery views) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.views = Objects.requireNonNull(views, "views");
    }

    /** The projection row of the instance, or null when the projector has not applied it yet. */
    public Map<String, Object> taskView(String tenant, String instanceId) {
        Optional<TaskViewRow> row = views.byInstance(tenant, instanceId);
        if (row.isEmpty()) {
            return null;
        }
        TaskViewRow view = row.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceStatus", view.instanceStatus());
        out.put("instanceVersion", view.instanceVersion());
        out.put("agentTaskId", view.agentTaskId());
        out.put("agentTaskStatus", view.agentTaskStatus());
        out.put("waitingForHuman", view.waitingForHuman());
        out.put("lastReason", view.lastReason());
        out.put("lastTransitionAt", text(view.lastTransitionAt()));
        out.put("lastOutboxId", view.lastOutboxId());
        out.put("updatedAt", text(view.updatedAt()));
        return out;
    }

    /**
     * The outbox rows of the instance: how many are not published yet, how many have failed, and the last failure
     * (event type and exception class name, never a message). The projection lags by these rows.
     */
    public Map<String, Object> outbox(String instanceId) {
        return Transactions.inTransaction(dataSource, connection -> {
            Map<String, Object> out = new LinkedHashMap<>();
            try (PreparedStatement count = connection.prepareStatement("""
                    select count(*) filter (where published_at is null) as unpublished,
                           count(*) filter (where failed_at is not null) as failed
                      from task.outbox where partition_key = ?
                    """)) {
                count.setString(1, instanceId);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    out.put("unpublished", rs.getLong("unpublished"));
                    out.put("failed", rs.getLong("failed"));
                }
            }
            try (PreparedStatement last = connection.prepareStatement("""
                    select event_type, last_error, failed_at from task.outbox
                     where partition_key = ? and failed_at is not null
                     order by id desc limit 1
                    """)) {
                last.setString(1, instanceId);
                try (ResultSet rs = last.executeQuery()) {
                    if (rs.next()) {
                        out.put("lastFailedEventType", rs.getString("event_type"));
                        out.put("lastError", rs.getString("last_error"));
                        out.put("lastFailedAt", text(instant(rs.getTimestamp("failed_at"))));
                    }
                }
            }
            return out;
        });
    }

    /** The most recent repair events about the instance, oldest first. */
    public List<Map<String, Object>> repairs(String instanceId) {
        return Transactions.inTransaction(dataSource, connection -> {
            List<Map<String, Object>> events = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    select kind, outcome, detail, actor_id, occurred_at from (
                        select id, kind, outcome, detail, actor_id, occurred_at
                          from task.repair_event where subject = ?
                         order by id desc limit ?) recent
                     order by id
                    """)) {
                select.setString(1, instanceId);
                select.setInt(2, MAX_REPAIRS);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("kind", rs.getString("kind"));
                        event.put("outcome", rs.getString("outcome"));
                        event.put("detail", rs.getString("detail"));
                        event.put("actor", rs.getString("actor_id"));
                        event.put("occurredAt", text(instant(rs.getTimestamp("occurred_at"))));
                        events.add(event);
                    }
                }
            }
            return events;
        });
    }

    private static Instant instant(Timestamp timestamp) throws SQLException {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** The instant as text, or null. Responses carry times as RFC 3339 text. */
    static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
