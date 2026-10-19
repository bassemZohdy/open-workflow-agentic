package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
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
 * The instance registry over plain JDBC (the {@code task.instance} table). Each method is one transaction, and each
 * change writes its history row in that transaction.
 */
@ApplicationScoped
public class JdbcInstanceStore implements InstanceStore {

    private static final String SELECT_COLUMNS = """
            select id, tenant, owner_kind, owner_id, idempotency_key, request_fingerprint, definition_id,
                   definition_version, input::text as input_text, status, engine_instance_id, start_attempts,
                   last_error, wait_deadline, version, created_at, updated_at
              from task.instance
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcInstanceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Insertion insertIfAbsent(InstanceRecord candidate, String reason) {
        requireInitial(candidate);
        return Transactions.inTransaction(dataSource, connection -> {
            int inserted;
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into task.instance (id, tenant, owner_kind, owner_id, idempotency_key,
                        request_fingerprint, definition_id, definition_version, input, status, engine_instance_id,
                        start_attempts, last_error, wait_deadline, version, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant, idempotency_key) do nothing
                    """)) {
                bindRow(insert, candidate);
                inserted = insert.executeUpdate();
            }
            if (inserted == 1) {
                writeHistory(connection, candidate.id(), 0L, null, InstanceStatus.STARTING, reason,
                        candidate.owner(), candidate.createdAt());
                outbox(candidate, 0L, null, InstanceStatus.STARTING, reason, candidate.owner(),
                        candidate.createdAt()).writeTo(connection);
                return new Insertion(candidate, true);
            }
            InstanceRecord existing = selectByKey(connection, candidate.tenant(), candidate.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("conflicting idempotency row vanished"));
            return new Insertion(existing, false);
        });
    }

    @Override
    public Optional<InstanceRecord> find(String tenant, Ulid id) {
        Objects.requireNonNull(id, "id");
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    SELECT_COLUMNS + " where tenant = ? and id = ?")) {
                select.setString(1, tenant);
                select.setString(2, id.value());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public InstanceRecord replace(InstanceRecord next, long expectedVersion, String reason, Subject actor) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(actor, "actor");
        if (next.version() != expectedVersion + 1) {
            throw new IllegalStateException("the new version must be " + (expectedVersion + 1)
                    + " but was " + next.version());
        }
        return Transactions.inTransaction(dataSource, connection -> {
            InstanceRecord current;
            try (PreparedStatement lock = connection.prepareStatement(
                    SELECT_COLUMNS + " where id = ? for update")) {
                lock.setString(1, next.id().value());
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("instance " + next.id() + " does not exist");
                    }
                    current = read(rs);
                }
            }
            if (current.version() != expectedVersion) {
                throw new StaleVersionException("instance " + next.id(), expectedVersion, current.version());
            }
            requireSameIdentity(current, next);
            if (current.engineInstanceId() != null
                    && !current.engineInstanceId().equals(next.engineInstanceId())) {
                throw new IllegalStateException("the engine instance id of " + next.id() + " is already set");
            }
            if (current.status() != next.status()) {
                InstanceStateMachine.requireMove(current.status(), next.status());
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    update task.instance
                       set status = ?, engine_instance_id = ?, start_attempts = ?, last_error = ?,
                           wait_deadline = ?, version = ?, updated_at = ?
                     where id = ? and version = ?
                    """)) {
                update.setString(1, next.status().name());
                update.setString(2, next.engineInstanceId());
                update.setInt(3, next.startAttempts());
                update.setString(4, next.lastError());
                update.setTimestamp(5, toTimestamp(next.waitDeadline()));
                update.setLong(6, next.version());
                update.setTimestamp(7, Timestamp.from(next.updatedAt()));
                update.setString(8, next.id().value());
                update.setLong(9, expectedVersion);
                if (update.executeUpdate() != 1) {
                    // Cannot happen while the row lock is held; kept as a guard against a future change to the lock.
                    throw new IllegalStateException("instance " + next.id() + " was not updated under its lock");
                }
            }
            writeHistory(connection, next.id(), next.version(), current.status(), next.status(), reason, actor,
                    next.updatedAt());
            outbox(next, next.version(), current.status(), next.status(), reason, actor, next.updatedAt())
                    .writeTo(connection);
            return next;
        });
    }

    @Override
    public List<HistoryEntry> history(Ulid id) {
        Objects.requireNonNull(id, "id");
        return Transactions.inTransaction(dataSource, connection -> {
            List<HistoryEntry> entries = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    select version, from_status, to_status, reason, actor_kind, actor_id, occurred_at
                      from task.instance_event
                     where instance_id = ?
                     order by version
                    """)) {
                select.setString(1, id.value());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new HistoryEntry(
                                rs.getLong("version"),
                                optionalStatus(rs.getString("from_status")),
                                InstanceStatus.valueOf(rs.getString("to_status")),
                                rs.getString("reason"),
                                new Subject(SubjectKind.valueOf(rs.getString("actor_kind")),
                                        rs.getString("actor_id")),
                                rs.getTimestamp("occurred_at").toInstant()));
                    }
                }
            }
            return entries;
        });
    }

    /** The outbox row that mirrors one history row of an instance (WP-105b). Same transaction, same time. */
    private static OutboxEntry outbox(InstanceRecord row, long version, InstanceStatus from, InstanceStatus to,
                                      String reason, Subject actor, Instant at) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenant", row.tenant());
        details.put("owner", Map.of("kind", row.owner().kind().name(), "id", row.owner().id()));
        details.put("definitionId", row.definition().definitionId());
        details.put("definitionVersion", row.definition().version());
        return OutboxEntry.transition(OutboxEntry.AggregateType.INSTANCE, row.id().value(), row.id().value(), version,
                from == null ? null : from.name(), to.name(), reason, actor, at, details);
    }

    private static void requireInitial(InstanceRecord candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.status() != InstanceStatus.STARTING || candidate.version() != 0L
                || candidate.engineInstanceId() != null || candidate.startAttempts() != 0) {
            throw new IllegalStateException("a new instance must be STARTING, version 0, with no engine id");
        }
    }

    private static void requireSameIdentity(InstanceRecord current, InstanceRecord next) {
        if (!current.id().equals(next.id()) || !current.tenant().equals(next.tenant())
                || !current.owner().equals(next.owner())
                || !current.idempotencyKey().equals(next.idempotencyKey())
                || !current.requestFingerprint().equals(next.requestFingerprint())
                || !current.definition().equals(next.definition())
                || !current.input().equals(next.input())
                || !current.createdAt().equals(next.createdAt())) {
            throw new IllegalStateException("identity and input of instance " + next.id() + " may not change");
        }
    }

    private static void bindRow(PreparedStatement insert, InstanceRecord row) throws SQLException {
        insert.setString(1, row.id().value());
        insert.setString(2, row.tenant());
        insert.setString(3, row.owner().kind().name());
        insert.setString(4, row.owner().id());
        insert.setString(5, row.idempotencyKey());
        insert.setString(6, row.requestFingerprint());
        insert.setString(7, row.definition().definitionId());
        insert.setString(8, row.definition().version());
        insert.setString(9, row.canonicalInput());
        insert.setString(10, row.status().name());
        insert.setString(11, row.engineInstanceId());
        insert.setInt(12, row.startAttempts());
        insert.setString(13, row.lastError());
        insert.setTimestamp(14, toTimestamp(row.waitDeadline()));
        insert.setLong(15, row.version());
        insert.setTimestamp(16, Timestamp.from(row.createdAt()));
        insert.setTimestamp(17, Timestamp.from(row.updatedAt()));
    }

    private static Optional<InstanceRecord> selectByKey(Connection connection, String tenant, String key)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                SELECT_COLUMNS + " where tenant = ? and idempotency_key = ?")) {
            select.setString(1, tenant);
            select.setString(2, key);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static void writeHistory(Connection connection, Ulid id, long version, InstanceStatus from,
                                     InstanceStatus to, String reason, Subject actor, Instant at)
            throws SQLException {
        try (PreparedStatement history = connection.prepareStatement("""
                insert into task.instance_event (instance_id, version, from_status, to_status, reason,
                    actor_kind, actor_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            history.setString(1, id.value());
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

    private static InstanceRecord read(ResultSet rs) throws SQLException {
        return new InstanceRecord(
                Ulid.parse(rs.getString("id")),
                rs.getString("tenant"),
                new Subject(SubjectKind.valueOf(rs.getString("owner_kind")), rs.getString("owner_id")),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                new DefinitionRef(rs.getString("definition_id"), rs.getString("definition_version")),
                Json.parse(rs.getString("input_text")),
                InstanceStatus.valueOf(rs.getString("status")),
                rs.getString("engine_instance_id"),
                rs.getInt("start_attempts"),
                rs.getString("last_error"),
                fromTimestamp(rs.getTimestamp("wait_deadline")),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static InstanceStatus optionalStatus(String text) {
        return text == null ? null : InstanceStatus.valueOf(text);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant fromTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
