package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxEntry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The writes that the reconciler makes outside {@link InstanceStore#replace}: the audit of repairs and the resume
 * epoch (WP-105c). The audit rows are not changes of state and have no outbox row. The resume epoch changes the row
 * (its version and history), so it writes its outbox row in the same transaction, as {@link JdbcInstanceStore} does
 * for every other change (WP-105b).
 */
@Singleton
public class RepairStore {

    /** The actor of every reconciler write. */
    static final String ACTOR = InstanceService.RUNTIME_ACTOR.id();

    /** The reason recorded for a resume epoch change, in history and in the outbox payload. */
    static final String REASON_RESUME = "reconciler.resume";

    private final DataSource dataSource;
    private final Clock clock;

    @Inject
    public RepairStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    RepairStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Writes one audit row. {@code kind} names the condition, {@code subject} is the id acted on (a registry id, an
     * engine id or an agent task id), {@code outcome} is what was done, {@code detail} is free text, never a message.
     */
    public void record(String kind, String subject, String outcome, String detail) {
        Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into task.repair_event (kind, subject, outcome, detail, actor_id)
                    values (?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, kind);
                insert.setString(2, subject);
                insert.setString(3, outcome);
                insert.setString(4, detail == null ? null : truncate(detail));
                insert.setString(5, ACTOR);
                insert.executeUpdate();
            }
            return null;
        });
    }

    /** True when an audit row with this kind and subject was written at or after {@code since}. */
    public boolean recordedSince(String kind, String subject, Instant since) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    select 1 from task.repair_event
                     where kind = ? and subject = ? and occurred_at >= ?
                     limit 1
                    """)) {
                select.setString(1, kind);
                select.setString(2, subject);
                select.setTimestamp(3, Timestamp.from(since));
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Adds one to the resume epoch of a registry row. In one transaction it writes the history row (status unchanged,
     * so from equals to) and the outbox row that mirrors it (WP-105b). The increment happens before the engine restarts
     * the instance, so a task that runs again sees the new epoch.
     *
     * @return the epoch after the increment
     */
    public long incrementResumeEpoch(String instanceId) {
        return Transactions.inTransaction(dataSource, connection -> {
            long version;
            String status;
            String tenant;
            String ownerKind;
            String ownerId;
            String definitionId;
            String definitionVersion;
            try (PreparedStatement lock = connection.prepareStatement("""
                    select version, status, tenant, owner_kind, owner_id, definition_id, definition_version
                      from task.instance where id = ? for update
                    """)) {
                lock.setString(1, instanceId);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("instance " + instanceId + " does not exist");
                    }
                    version = rs.getLong("version");
                    status = rs.getString("status");
                    tenant = rs.getString("tenant");
                    ownerKind = rs.getString("owner_kind");
                    ownerId = rs.getString("owner_id");
                    definitionId = rs.getString("definition_id");
                    definitionVersion = rs.getString("definition_version");
                }
            }
            long nextVersion = version + 1;
            Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
            long epoch;
            try (PreparedStatement update = connection.prepareStatement("""
                    update task.instance
                       set resume_epoch = resume_epoch + 1, version = ?, updated_at = ?
                     where id = ? and version = ?
                    returning resume_epoch
                    """)) {
                update.setLong(1, nextVersion);
                update.setTimestamp(2, Timestamp.from(now));
                update.setString(3, instanceId);
                update.setLong(4, version);
                try (ResultSet rs = update.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("instance " + instanceId + " was not updated under its lock");
                    }
                    epoch = rs.getLong(1);
                }
            }
            try (PreparedStatement history = connection.prepareStatement("""
                    insert into task.instance_event (instance_id, version, from_status, to_status, reason,
                        actor_kind, actor_id, occurred_at)
                    values (?, ?, ?, ?, ?, 'SERVICE', ?, ?)
                    """)) {
                history.setString(1, instanceId);
                history.setLong(2, nextVersion);
                history.setString(3, status);
                history.setString(4, status);
                history.setString(5, REASON_RESUME);
                history.setString(6, ACTOR);
                history.setTimestamp(7, Timestamp.from(now));
                history.executeUpdate();
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("tenant", tenant);
            details.put("owner", Map.of("kind", ownerKind, "id", ownerId));
            details.put("definitionId", definitionId);
            details.put("definitionVersion", definitionVersion);
            details.put("resumeEpoch", epoch);
            OutboxEntry.transition(OutboxEntry.AggregateType.INSTANCE, instanceId, instanceId, nextVersion, status,
                    status, REASON_RESUME, InstanceService.RUNTIME_ACTOR, now, details).writeTo(connection);
            return epoch;
        });
    }

    private static String truncate(String text) {
        return text.length() <= 512 ? text : text.substring(0, 512);
    }
}
