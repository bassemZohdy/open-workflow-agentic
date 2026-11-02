package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Proposed workflow-owner lease primitive over V82. It is not wired into workflow start, restore, cancellation,
 * checkpointing or external calls. A claim is attribution data, not a capability, and must only be constructed and
 * used by a trusted runtime. A database check alone cannot stop an external side effect already in flight.
 */
public final class JdbcWorkflowOwnerLease {
    public record Binding(String tenant, Ulid instanceId, String applicationId, String engineInstanceId,
                          DefinitionRef definition) {
        public Binding {
            if (tenant == null || tenant.isBlank() || applicationId == null || applicationId.isBlank()
                    || engineInstanceId == null || engineInstanceId.isBlank())
                throw new IllegalArgumentException("lease binding requires tenant, application and engine instance");
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(definition, "definition");
        }
    }
    public record Claim(Binding binding, UUID ownerId, long epoch) {
        public Claim {
            Objects.requireNonNull(binding, "binding"); Objects.requireNonNull(ownerId, "ownerId");
            if (epoch < 1) throw new IllegalArgumentException("lease epoch must be positive");
        }
    }
    public record Lease(Claim claim, Instant expiresAt) { }
    private record Row(Binding binding, UUID ownerId, long epoch, String state, Instant expiresAt) { }

    private final DataSource data;
    public JdbcWorkflowOwnerLease(DataSource data) { this.data = Objects.requireNonNull(data, "data"); }

    /** A same-owner retry returns the original expiry; an expired or released generation is never reused. */
    public Optional<Lease> acquire(Binding binding, UUID owner, Duration ttl) {
        Objects.requireNonNull(binding); Objects.requireNonNull(owner);
        long millis = ttlMillis(ttl);
        return transaction(c -> {
            if (!registry(c, binding, true)) return Optional.empty();
            Row row = read(c, binding);
            Instant now = databaseNow(c);
            if (row != null && row.state().equals("ACTIVE") && row.expiresAt().isAfter(now))
                return row.ownerId().equals(owner) ? Optional.of(new Lease(new Claim(binding, owner, row.epoch()), row.expiresAt())) : Optional.empty();
            long epoch = row == null ? 1 : Math.addExact(row.epoch(), 1);
            Instant expiry = now.plusMillis(millis);
            if (row == null) {
                try (var q = c.prepareStatement("""
                        insert into task.workflow_owner_lease
                          (instance_id,tenant,application_id,engine_instance_id,definition_id,definition_version,
                           owner_id,epoch,state,acquired_at,renewed_at,expires_at)
                        values (?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                        """)) {
                    q.setString(1, binding.instanceId().value()); q.setString(2, binding.tenant());
                    q.setString(3, binding.applicationId()); q.setString(4, binding.engineInstanceId());
                    q.setString(5, binding.definition().definitionId()); q.setString(6, binding.definition().version());
                    q.setObject(7, owner); q.setLong(8, epoch); timestamp(q, 9, now); timestamp(q, 10, now); timestamp(q, 11, expiry);
                    q.executeUpdate();
                }
            } else {
                try (var q = c.prepareStatement("update task.workflow_owner_lease set owner_id=?,epoch=?,state='ACTIVE',"
                        + "acquired_at=?,renewed_at=?,expires_at=? where instance_id=?")) {
                    q.setObject(1, owner); q.setLong(2, epoch); timestamp(q, 3, now); timestamp(q, 4, now);
                    timestamp(q, 5, expiry); q.setString(6, binding.instanceId().value()); q.executeUpdate();
                }
            }
            return Optional.of(new Lease(new Claim(binding, owner, epoch), expiry));
        });
    }

    public Optional<Lease> renew(Claim claim, Duration ttl) {
        Objects.requireNonNull(claim); long millis = ttlMillis(ttl);
        return transaction(c -> {
            if (!registry(c, claim.binding(), true)) return Optional.empty();
            Row row = read(c, claim.binding()); Instant now = databaseNow(c);
            if (!current(row, claim, now)) return Optional.empty();
            Instant expiry = now.plusMillis(millis);
            if (expiry.isBefore(row.expiresAt())) expiry = row.expiresAt();
            try (var q = c.prepareStatement("update task.workflow_owner_lease set renewed_at=?,expires_at=? where instance_id=?")) {
                timestamp(q, 1, now); timestamp(q, 2, expiry); q.setString(3, claim.binding().instanceId().value()); q.executeUpdate();
            }
            return Optional.of(new Lease(claim, expiry));
        });
    }

    /** Releases only this exact generation; a stale process cannot release its successor's lease. */
    public boolean release(Claim claim) {
        Objects.requireNonNull(claim);
        return transaction(c -> {
            if (!registry(c, claim.binding(), false)) return false;
            Row row = read(c, claim.binding());
            if (row == null || !row.state().equals("ACTIVE") || !row.ownerId().equals(claim.ownerId()) || row.epoch() != claim.epoch()) return false;
            try (var q = c.prepareStatement("update task.workflow_owner_lease set state='RELEASED' where instance_id=?")) {
                q.setString(1, claim.binding().instanceId().value()); q.executeUpdate();
            }
            return true;
        });
    }

    /**
     * Runs database-only work while the registry and owner rows are locked. A post-work check rolls back if it sees
     * expired authority or a stopped instance; the locks prevent a successor taking ownership until this transaction
     * ends. Expiry can still occur between that check and commit. The callback must not commit or perform I/O
     * outside this connection. A failed commit acknowledgement may still represent a committed transaction; callers
     * must reconcile the same work instead of assuming it rolled back. This does not fence an external business effect.
     */
    public <T> T withCurrentLease(Claim claim, Transactions.Work<T> work) {
        Objects.requireNonNull(claim); Objects.requireNonNull(work);
        return transaction(c -> {
            requireCurrent(c, claim);
            T value = work.run(c);
            requireCurrent(c, claim);
            return value;
        });
    }

    static void requireCurrent(Connection c, Claim claim) throws SQLException {
        if (!registry(c, claim.binding(), true) || !current(read(c, claim.binding()), claim, databaseNow(c)))
            throw new IllegalStateException("workflow owner lease is not current");
    }
    private static boolean current(Row row, Claim claim, Instant now) {
        return row != null && row.state().equals("ACTIVE") && row.ownerId().equals(claim.ownerId())
                && row.epoch() == claim.epoch() && row.expiresAt().isAfter(now);
    }
    private static boolean registry(Connection c, Binding b, boolean requireActive) throws SQLException {
        try (var q = c.prepareStatement("select engine_instance_id,definition_id,definition_version,status "
                + "from task.instance where id=? and tenant=? for update")) {
            q.setString(1, b.instanceId().value()); q.setString(2, b.tenant());
            try (var r = q.executeQuery()) {
                if (!r.next() || !b.engineInstanceId().equals(r.getString(1))
                        || !b.definition().definitionId().equals(r.getString(2))
                        || !b.definition().version().equals(r.getString(3))) return false;
                return !requireActive || java.util.Set.of("STARTING", "RUNNING", "WAITING").contains(r.getString(4));
            }
        }
    }
    private static Row read(Connection c, Binding b) throws SQLException {
        try (var q = c.prepareStatement("select tenant,application_id,engine_instance_id,definition_id,definition_version,"
                + "owner_id,epoch,state,expires_at from task.workflow_owner_lease where instance_id=? for update")) {
            q.setString(1, b.instanceId().value());
            try (var r = q.executeQuery()) {
                if (!r.next()) return null;
                if (!b.tenant().equals(r.getString(1)) || !b.applicationId().equals(r.getString(2))
                        || !b.engineInstanceId().equals(r.getString(3)) || !b.definition().definitionId().equals(r.getString(4))
                        || !b.definition().version().equals(r.getString(5)))
                    throw new IllegalStateException("workflow owner lease binding differs");
                return new Row(b, r.getObject(6, UUID.class), r.getLong(7), r.getString(8), r.getTimestamp(9).toInstant());
            }
        }
    }
    private static Instant databaseNow(Connection c) throws SQLException {
        try (var q = c.prepareStatement("select clock_timestamp()" ); var r = q.executeQuery()) {
            if (!r.next()) throw new SQLException("database clock unavailable"); return r.getTimestamp(1).toInstant();
        }
    }
    private static void timestamp(java.sql.PreparedStatement q, int index, Instant time) throws SQLException {
        q.setTimestamp(index, Timestamp.from(time));
    }
    private static long ttlMillis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.compareTo(Duration.ofMillis(250)) < 0 || ttl.compareTo(Duration.ofSeconds(30)) > 0
                || ttl.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("lease TTL must be whole milliseconds from 250ms through 30s");
        return ttl.toMillis();
    }
    private <T> T transaction(Transactions.Work<T> work) {
        return Transactions.inBoundedPostgresTransaction(data, work, 2000, 5000, 5000);
    }
}
