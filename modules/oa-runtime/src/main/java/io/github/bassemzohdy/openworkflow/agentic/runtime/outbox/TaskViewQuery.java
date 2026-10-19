package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Reads {@code task.task_view} (WP-105b). Every query is for one tenant. Pages are ordered by instance id, which is a
 * ULID, so the order is creation order. The cursor is an opaque text: the instance id of the last row of the page,
 * encoded.
 */
@ApplicationScoped
public class TaskViewQuery {

    private static final String COLUMNS = """
            select instance_id, tenant, owner_kind, owner_id, definition_id, definition_version, instance_status,
                   instance_version, agent_task_id, agent_task_status, agent_outbox_id, waiting_for_human,
                   last_reason, last_transition_at, last_outbox_id, updated_at
              from task.task_view
            """;

    private final DataSource dataSource;

    @Inject
    public TaskViewQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** One page of a tenant's instances, optionally narrowed by status and definition. */
    public TaskViewPage list(TaskViewFilter filter, PageRequest page) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        String afterId = decodeCursor(page.cursor());
        StringBuilder sql = new StringBuilder(COLUMNS).append(" where tenant = ?");
        List<Object> params = new ArrayList<>();
        params.add(filter.tenant());
        if (filter.status() != null) {
            sql.append(" and instance_status = ?");
            params.add(filter.status().name());
        }
        if (filter.definitionId() != null) {
            sql.append(" and definition_id = ?");
            params.add(filter.definitionId());
        }
        if (afterId != null) {
            sql.append(" and instance_id > ?");
            params.add(afterId);
        }
        sql.append(" order by instance_id limit ?");
        params.add(page.limit() + 1);

        List<TaskViewRow> rows = Transactions.inTransaction(dataSource, connection -> read(connection, sql.toString(),
                params));
        boolean more = rows.size() > page.limit();
        List<TaskViewRow> items = more ? rows.subList(0, page.limit()) : rows;
        String next = more ? encodeCursor(items.get(items.size() - 1).instanceId()) : null;
        return new TaskViewPage(List.copyOf(items), next);
    }

    /** The view row of one instance in one tenant, or empty. Another tenant's instance is empty. */
    public Optional<TaskViewRow> byInstance(String tenant, String instanceId) {
        List<TaskViewRow> rows = Transactions.inTransaction(dataSource, connection -> read(connection,
                COLUMNS + " where tenant = ? and instance_id = ?", List.of(tenant, instanceId)));
        return rows.stream().findFirst();
    }

    /**
     * Every row, in instance id order. For verification and maintenance (comparing a rebuild with the live view); a
     * request path uses {@link #list}.
     */
    public List<TaskViewRow> snapshot() {
        return Transactions.inTransaction(dataSource, connection -> read(connection,
                COLUMNS + " order by instance_id", List.of()));
    }

    private static List<TaskViewRow> read(Connection connection, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                select.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = select.executeQuery()) {
                List<TaskViewRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(readRow(rs));
                }
                return rows;
            }
        }
    }

    private static TaskViewRow readRow(ResultSet rs) throws SQLException {
        Long agentOutbox = rs.getObject("agent_outbox_id", Long.class);
        return new TaskViewRow(
                rs.getString("instance_id"),
                rs.getString("tenant"),
                rs.getString("owner_kind"),
                rs.getString("owner_id"),
                rs.getString("definition_id"),
                rs.getString("definition_version"),
                rs.getString("instance_status"),
                rs.getLong("instance_version"),
                rs.getString("agent_task_id"),
                rs.getString("agent_task_status"),
                agentOutbox,
                rs.getBoolean("waiting_for_human"),
                rs.getString("last_reason"),
                rs.getTimestamp("last_transition_at").toInstant(),
                rs.getLong("last_outbox_id"),
                rs.getTimestamp("updated_at").toInstant());
    }

    static String encodeCursor(String instanceId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(instanceId.getBytes(StandardCharsets.UTF_8));
    }

    static String decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor is not valid", e);
        }
        if (decoded.length() != 26) {
            throw new IllegalArgumentException("cursor is not valid");
        }
        return decoded;
    }
}
