package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.TaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The {@code task.agent_task} table as a {@link TaskStore} (ADR 0020, decision 1). Every write is one transaction that
 * also writes its history row and its outbox row (WP-105b). Ownership is not decided here: the runtime asks
 * {@link #findByThread} before it reads, writes or resumes a thread.
 *
 * <p>Each write first locks the workflow instance row. That serializes the outbox writes of one instance, so the
 * outbox id order of an instance is its commit order (see {@code V14__task_outbox.sql}).
 */
@ApplicationScoped
public class JdbcTaskStore implements TaskStore {

    static final String REASON_CREATED = "task.created";
    static final String REASON_STATUS_CHANGED = "task.status.changed";
    private static final Subject RUNTIME = InstanceService.RUNTIME_ACTOR;

    private static final String SELECT_COLUMNS = """
            select id, instance_id, tenant, owner_kind, owner_id, thread_name, status, contract::text as contract_text,
                   version
              from task.agent_task
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcTaskStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public AgentTaskRecord insert(AgentTaskRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.version() != 0L) {
            throw new IllegalStateException("a new task must have version 0");
        }
        if (record.status() != AgentTaskStateMachine.initial()) {
            throw new IllegalStateException("a new task must be " + AgentTaskStateMachine.initial());
        }
        return Transactions.inTransaction(dataSource, connection -> {
            Map<String, Object> instance = lockInstance(connection, record.workflowInstance(), record.tenant())
                    .orElseThrow(() -> new IllegalStateException(
                            "the task's instance does not exist in this tenant"));
            Instant at;
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into task.agent_task (id, instance_id, tenant, owner_kind, owner_id, thread_name, status,
                        contract, version)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                    returning updated_at
                    """)) {
                insert.setString(1, record.taskId().value());
                insert.setString(2, record.workflowInstance());
                insert.setString(3, record.tenant());
                insert.setString(4, record.owner().kind().name());
                insert.setString(5, record.owner().id());
                insert.setString(6, record.threadId().value());
                insert.setString(7, record.status().name());
                insert.setString(8, AgentTaskCodec.encode(record.contract()));
                insert.setLong(9, record.version());
                try (ResultSet rs = insert.executeQuery()) {
                    rs.next();
                    at = rs.getTimestamp("updated_at").toInstant();
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    throw new IllegalStateException("the task id or the thread id already exists", e);
                }
                throw e;
            }
            writeHistory(connection, record.taskId(), 0L, null, record.status(), REASON_CREATED, RUNTIME, at);
            outbox(record, 0L, null, record.status(), REASON_CREATED, at, instance).writeTo(connection);
            return record;
        });
    }

    @Override
    public Optional<AgentTaskRecord> find(Ulid taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return one("where id = ?", taskId.value());
    }

    @Override
    public Optional<AgentTaskRecord> findByThread(ThreadId threadId) {
        Objects.requireNonNull(threadId, "threadId");
        return one("where thread_name = ?", threadId.value());
    }

    @Override
    public AgentTaskRecord replace(AgentTaskRecord next, long expectedVersion) {
        Objects.requireNonNull(next, "next");
        if (next.version() != expectedVersion + 1) {
            throw new IllegalStateException("the new version must be " + (expectedVersion + 1)
                    + " but was " + next.version());
        }
        return Transactions.inTransaction(dataSource, connection -> {
            Map<String, Object> instance = lockInstance(connection, next.workflowInstance(), next.tenant())
                    .orElseThrow(() -> new IllegalStateException(
                            "the task's instance does not exist in this tenant"));
            AgentTaskRecord current = lockedRow(connection, next.taskId());
            if (current.version() != expectedVersion) {
                throw new StaleVersionException("agent task " + next.taskId(), expectedVersion, current.version());
            }
            requireSameIdentity(current, next);
            if (current.status() != next.status()) {
                AgentTaskStateMachine.requireMove(current.status(), next.status());
            }
            Instant at;
            try (PreparedStatement update = connection.prepareStatement("""
                    update task.agent_task
                       set status = ?, version = ?, updated_at = now()
                     where id = ? and version = ?
                    returning updated_at
                    """)) {
                update.setString(1, next.status().name());
                update.setLong(2, next.version());
                update.setString(3, next.taskId().value());
                update.setLong(4, expectedVersion);
                try (ResultSet rs = update.executeQuery()) {
                    if (!rs.next()) {
                        // Cannot happen while the row lock is held; kept as a guard against a future change to the lock.
                        throw new IllegalStateException("agent task " + next.taskId() + " was not updated under its lock");
                    }
                    at = rs.getTimestamp("updated_at").toInstant();
                }
            }
            writeHistory(connection, next.taskId(), next.version(), current.status(), next.status(),
                    REASON_STATUS_CHANGED, RUNTIME, at);
            outbox(next, next.version(), current.status(), next.status(), REASON_STATUS_CHANGED, at, instance)
                    .writeTo(connection);
            return next;
        });
    }

    private Optional<AgentTaskRecord> one(String where, String value) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_COLUMNS + " " + where)) {
                select.setString(1, value);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Locks the instance row of a task, the partition of its outbox rows. Returns the definition of the instance, which
     * the outbox payload carries, or empty when the instance is not in this tenant.
     */
    private static Optional<Map<String, Object>> lockInstance(Connection connection, String instanceId, String tenant)
            throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "select definition_id, definition_version from task.instance where id = ? and tenant = ? for update")) {
            lock.setString(1, instanceId);
            lock.setString(2, tenant);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("definitionId", rs.getString("definition_id"));
                definition.put("definitionVersion", rs.getString("definition_version"));
                return Optional.of(definition);
            }
        }
    }

    private static AgentTaskRecord lockedRow(Connection connection, Ulid taskId) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(SELECT_COLUMNS + " where id = ? for update")) {
            lock.setString(1, taskId.value());
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("agent task " + taskId + " does not exist");
                }
                return read(rs);
            }
        }
    }

    private static void requireSameIdentity(AgentTaskRecord current, AgentTaskRecord next) {
        if (!current.taskId().equals(next.taskId()) || !current.tenant().equals(next.tenant())
                || !current.owner().equals(next.owner())
                || !current.workflowInstance().equals(next.workflowInstance())
                || !current.threadId().equals(next.threadId())
                || !current.contract().equals(next.contract())) {
            throw new IllegalStateException("identity, owner and contract of agent task " + next.taskId()
                    + " may not change");
        }
    }

    private static void writeHistory(Connection connection, Ulid taskId, long version, AgentTaskStatus from,
                                     AgentTaskStatus to, String reason, Subject actor, Instant at)
            throws SQLException {
        try (PreparedStatement history = connection.prepareStatement("""
                insert into task.agent_task_event (agent_task_id, version, from_status, to_status, reason,
                    actor_kind, actor_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            history.setString(1, taskId.value());
            history.setLong(2, version);
            history.setString(3, from == null ? null : from.name());
            history.setString(4, to.name());
            history.setString(5, reason);
            history.setString(6, actor.kind().name());
            history.setString(7, actor.id());
            history.setTimestamp(8, Timestamp.from(at));
            history.executeUpdate();
        }
    }

    /** The outbox row that mirrors one history row of an agent task (WP-105b). Same transaction, same time. */
    private static OutboxEntry outbox(AgentTaskRecord task, long version, AgentTaskStatus from, AgentTaskStatus to,
                                      String reason, Instant at, Map<String, Object> instance) {
        Map<String, Object> details = new LinkedHashMap<>(instance);
        details.put("tenant", task.tenant());
        details.put("owner", Map.of("kind", task.owner().kind().name(), "id", task.owner().id()));
        details.put("instanceId", task.workflowInstance());
        details.put("taskId", task.taskId().value());
        details.put("threadId", task.threadId().value());
        return OutboxEntry.transition(OutboxEntry.AggregateType.AGENT_TASK, task.taskId().value(),
                task.workflowInstance(), version, from == null ? null : from.name(), to.name(), reason, RUNTIME, at,
                details);
    }

    private static AgentTaskRecord read(ResultSet rs) throws SQLException {
        AgentTaskContract contract = AgentTaskCodec.decode(rs.getString("contract_text"));
        return new AgentTaskRecord(
                Ulid.parse(rs.getString("id")),
                rs.getString("tenant"),
                new Subject(SubjectKind.valueOf(rs.getString("owner_kind")), rs.getString("owner_id")),
                rs.getString("instance_id"),
                ThreadId.parse(rs.getString("thread_name")),
                contract,
                AgentTaskStatus.valueOf(rs.getString("status")),
                rs.getLong("version"));
    }
}
