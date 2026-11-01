package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Read-only queries the reconciler and the observer need (WP-105c). They return ids and small values; every row that
 * is changed is then loaded and written through {@link InstanceStore#replace}, so the store's guards still apply.
 */
@Singleton
public class RegistryScan {

    /** A registry row, located by id, with its tenant (the tenant is needed to load it). */
    public record Ref(String id, String tenant) {
    }

    /** An agent run that is recorded as running, with the liveness fields the reconciler reads. */
    public record AgentRun(String taskId, String instanceId, AgentTaskStatus status, String bridge, Instant leaseUntil) {
    }

    private final DataSource dataSource;

    @Inject
    public RegistryScan(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Rows in the given statuses with an id after the cursor, in id order. Pass an empty cursor to start. */
    public List<Ref> page(Set<InstanceStatus> statuses, String afterId, int limit) {
        String sql = "select id, tenant from task.instance where status in (" + marks(statuses.size())
                + ") and id > ? order by id limit ?";
        return query(sql, statement -> {
            int next = bindNames(statement, statuses, 1);
            statement.setString(next, afterId);
            statement.setInt(next + 1, limit);
        }, rs -> new Ref(rs.getString("id"), rs.getString("tenant")));
    }

    /** Rows in the given statuses that have not changed since {@code updatedBefore}, oldest first. */
    public List<Ref> staleInStatus(Set<InstanceStatus> statuses, Instant updatedBefore, int limit) {
        String sql = "select id, tenant from task.instance where status in (" + marks(statuses.size())
                + ") and updated_at < ? order by updated_at limit ?";
        return query(sql, statement -> {
            int next = bindNames(statement, statuses, 1);
            statement.setTimestamp(next, Timestamp.from(updatedBefore));
            statement.setInt(next + 1, limit);
        }, rs -> new Ref(rs.getString("id"), rs.getString("tenant")));
    }

    /** Waiting rows whose wait deadline has passed at {@code now} (ADR 0019, Deadlines). */
    public List<Ref> waitsPastDeadline(Instant now, int limit) {
        return query("""
                select id, tenant from task.instance
                 where status = 'WAITING' and wait_deadline is not null and wait_deadline < ?
                 order by wait_deadline limit ?
                """, statement -> {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
        }, rs -> new Ref(rs.getString("id"), rs.getString("tenant")));
    }

    /** Running or waiting rows created before {@code cutoff}: instances past their maximum lifetime. */
    public List<Ref> livePastLifetime(Instant cutoff, int limit) {
        return query("""
                select id, tenant from task.instance
                 where status in ('RUNNING', 'WAITING') and created_at < ?
                 order by created_at limit ?
                """, statement -> {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            statement.setInt(2, limit);
        }, rs -> new Ref(rs.getString("id"), rs.getString("tenant")));
    }

    /** The registry row that holds this engine instance, if any. */
    public Optional<Ref> byEngineInstanceId(String engineInstanceId) {
        List<Ref> rows = query("select id, tenant from task.instance where engine_instance_id = ?", statement ->
                statement.setString(1, engineInstanceId), rs -> new Ref(rs.getString("id"), rs.getString("tenant")));
        return rows.stream().findFirst();
    }

    /** The resume epoch of a registry row, by registry id (ADR 0019; WP-105c). Empty when no row has this id. */
    public OptionalLong resumeEpoch(String instanceId) {
        List<Long> epochs = query("select resume_epoch from task.instance where id = ?", statement ->
                statement.setString(1, instanceId), rs -> rs.getLong("resume_epoch"));
        return epochs.isEmpty() ? OptionalLong.empty() : OptionalLong.of(epochs.get(0));
    }

    /**
     * The resume epoch of the row that holds this engine instance. A task sees the engine's id (Flow's
     * {@code X-Flow-Instance-Id}), so this is the lookup a task uses. Empty when no row holds the engine id.
     */
    public OptionalLong resumeEpochOfEngineInstance(String engineInstanceId) {
        List<Long> epochs = query("select resume_epoch from task.instance where engine_instance_id = ?", statement ->
                statement.setString(1, engineInstanceId), rs -> rs.getLong("resume_epoch"));
        return epochs.isEmpty() ? OptionalLong.empty() : OptionalLong.of(epochs.get(0));
    }

    /**
     * Agent runs recorded in RUNNING or RESUMING whose lease is missing or ended before {@code now}. A run without a
     * live lease belongs to a process that is gone, or to none (ADR 0023, decision 7; see {@link AgentRunPolicy}).
     */
    public List<AgentRun> agentRunsWithoutLiveLease(Instant now, int limit) {
        return query("""
                select id, instance_id, status, bridge, lease_until from task.agent_task
                 where status in ('RUNNING', 'RESUMING') and (lease_until is null or lease_until < ?)
                 order by id limit ?
                """, statement -> {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
        }, rs -> new AgentRun(rs.getString("id"), rs.getString("instance_id"),
                AgentTaskStatus.valueOf(rs.getString("status")), rs.getString("bridge"),
                rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()));
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static int bindNames(PreparedStatement statement, Set<InstanceStatus> statuses, int from)
            throws SQLException {
        int index = from;
        for (InstanceStatus status : statuses.stream().sorted().collect(Collectors.toList())) {
            statement.setString(index++, status.name());
        }
        return index;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        return Transactions.inTransaction(dataSource, (Connection connection) -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                }
            }
            return out;
        });
    }
}
