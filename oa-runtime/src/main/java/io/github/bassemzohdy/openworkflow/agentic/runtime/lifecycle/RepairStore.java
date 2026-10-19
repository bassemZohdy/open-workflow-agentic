package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The writes that the reconciler makes outside {@link InstanceStore#replace}: the audit of repairs and the resume
 * epoch (WP-105c). Both are append-only or additive, so the existing write path of the registry is not changed.
 */
@Singleton
public class RepairStore {

    /** The actor of every reconciler write. */
    static final String ACTOR = InstanceService.RUNTIME_ACTOR.id();

    private final DataSource dataSource;

    @Inject
    public RepairStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
     * Adds one to the resume epoch of a registry row and writes its history row (status unchanged, so from equals
     * to). The increment happens before the engine restarts the instance, so a task that runs again sees the new
     * epoch.
     *
     * @return the epoch after the increment
     */
    public long incrementResumeEpoch(String instanceId) {
        return Transactions.inTransaction(dataSource, connection -> {
            long version;
            String status;
            try (PreparedStatement lock = connection.prepareStatement(
                    "select version, status from task.instance where id = ? for update")) {
                lock.setString(1, instanceId);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("instance " + instanceId + " does not exist");
                    }
                    version = rs.getLong("version");
                    status = rs.getString("status");
                }
            }
            long nextVersion = version + 1;
            long epoch;
            try (PreparedStatement update = connection.prepareStatement("""
                    update task.instance
                       set resume_epoch = resume_epoch + 1, version = ?, updated_at = now()
                     where id = ? and version = ?
                    returning resume_epoch
                    """)) {
                update.setLong(1, nextVersion);
                update.setString(2, instanceId);
                update.setLong(3, version);
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
                    values (?, ?, ?, ?, 'reconciler.resume', 'SERVICE', ?, now())
                    """)) {
                history.setString(1, instanceId);
                history.setLong(2, nextVersion);
                history.setString(3, status);
                history.setString(4, status);
                history.setString(5, ACTOR);
                history.executeUpdate();
            }
            return epoch;
        });
    }

    private static String truncate(String text) {
        return text.length() <= 512 ? text : text.substring(0, 512);
    }
}
