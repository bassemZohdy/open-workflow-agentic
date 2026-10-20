package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Durable dispatch reservation/acknowledgement storage. Matching alone never authorizes a callback. A bounded runtime
 * coordinator must retain the claim through actual checkpoint acknowledgement, and release it on process shutdown.
 * This store does not register SDK listeners, prove restore identity or implement manual-resolution authorization.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcInboxDispatch {
    private static final int LOCK_CLASS = 0x4f41494e; // OAIN, separate from lifecycle/effect/audit lock classes.
    private static final Subject RECOVERY = new Subject(SubjectKind.SERVICE, "system:event-recovery");
    private final DataSource dataSource;
    private final AuditSink audit;

    public record Reserved(InboxWait occurrence, InboxMessage receipt, int attempt, Instant deadline) { }
    public enum Acknowledgement { ACKNOWLEDGED, NO_PROOF, NO_RESERVATION, CANCELLED, EXPIRED }

    @Inject public JdbcInboxDispatch(DataSource dataSource, AuditTrail audit) { this(dataSource, (AuditSink) audit); }
    public JdbcInboxDispatch(DataSource dataSource, AuditSink audit) {
        this.dataSource = Objects.requireNonNull(dataSource); this.audit = Objects.requireNonNull(audit);
    }

    /** Repeat registration retains the first absolute deadline, including across restart. */
    public Instant register(InboxWait wait) {
        return Transactions.inTransaction(dataSource, c -> {
            Registry registry = instance(c, wait); // The lock order is instance, delivery, signal/message.
            if (!Set.of("STARTING", "RUNNING", "WAITING").contains(registry.status)) {
                throw new IllegalStateException("cannot register a wait on a terminal/cancelled instance");
            }
            // Return the original even after expiry; a retry must neither re-arm nor fail a duplicate insert's
            // deadline constraint. The instance lock serializes registrations for this instance.
            try (var s = c.prepareStatement("select identity_digest,deadline from inbox.delivery "
                    + "where tenant=? and instance_id=? and wait_key=? for update")) {
                route(s, wait);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        if (!r.getString(1).equals(wait.digest())) throw new IllegalStateException("wait identity changed");
                        return r.getTimestamp(2).toInstant();
                    }
                }
            }
            if (registry.deadline != null && !registry.deadline.isAfter(databaseNow(c))) {
                throw new IllegalStateException("instance wait deadline already expired");
            }
            try (var s = c.prepareStatement("""
                    with instant as materialized (select clock_timestamp() as t)
                    insert into inbox.delivery (tenant,instance_id,wait_key,identity_digest,state,registered_at,deadline)
                    select ?,?,?,?,'REGISTERED',t,least(t+(?::bigint * interval '1 millisecond'),?::timestamptz)
                    from instant
                    on conflict do nothing
                    """)) {
                route(s, wait); s.setString(4, wait.digest()); s.setLong(5, wait.maxWait().toMillis());
                s.setTimestamp(6, registry.deadline == null ? null : Timestamp.from(registry.deadline)); s.executeUpdate();
            }
            try (var s = c.prepareStatement("select identity_digest,deadline from inbox.delivery "
                    + "where tenant=? and instance_id=? and wait_key=? for update")) {
                route(s, wait);
                try (var r = s.executeQuery()) {
                    if (!r.next() || !r.getString(1).equals(wait.digest())) throw new IllegalStateException("wait identity changed");
                    return r.getTimestamp(2).toInstant();
                }
            }
        });
    }

    /**
     * Serializes cooperating live claims. The future SDK coordinator must additionally fence lost claims against
     * engine progress; a session lock alone cannot stop an old in-memory callback after a network partition.
     * Requires a session-preserving datasource (not transaction-pooled PostgreSQL connections).
     */
    public Optional<Claim> claim(InboxWait wait) {
        int hash = ByteBuffer.wrap(java.util.HexFormat.of().parseHex(wait.digest())).getInt();
        try {
            Connection c = dataSource.getConnection(); boolean handedOff = false;
            try (var s = c.prepareStatement("select pg_try_advisory_lock(?,?)")) {
                s.setInt(1, LOCK_CLASS); s.setInt(2, hash);
                boolean acquired;
                try (var r = s.executeQuery()) { r.next(); acquired = r.getBoolean(1); }
                if (!acquired) return Optional.empty();
                Claim claim = new Claim(this, c, wait, hash); handedOff = true;
                return Optional.of(claim);
            } catch (SQLException failure) {
                handedOff = false;
                // Acquisition may have reached PostgreSQL even if reading its reply failed. Do not return an
                // ambiguously locked physical session to the pool.
                discard(c, failure); throw failure;
            } finally { if (!handedOff) c.close(); }
        } catch (SQLException unavailable) { throw new StoreUnavailableException("inbox dispatch claim unavailable", unavailable); }
    }

    /**
     * The cancellation/deadline race linearizes at this committed reservation. Audit must succeed before the caller
     * receives permission to invoke its callback. A failed audit leaves a visible, unacknowledged attempt for recovery.
     */
    public Optional<Reserved> reserve(Claim claim, InboxCheckpointProof proof) {
        requireClaim(claim); Objects.requireNonNull(proof);
        if (!claim.attempted.compareAndSet(false, true)) throw new IllegalStateException("one reservation per claim");
        // A crash after checkpoint but before acknowledgement must not invoke the callback again. Proof is always
        // queried before replay; there is no production default that equates callback return with a checkpoint.
        if (acknowledge(claim, proof) == Acknowledgement.ACKNOWLEDGED) return Optional.empty();
        Optional<Reserved> reserved = inClaim(claim, c -> {
            Registry registry = instance(c, claim.wait);
            String status = registry.status;
            var current = current(c, claim.wait);
            if (current.isEmpty() || terminal(current.get().state)) return Optional.empty();
            // Close the proof-read/reservation gap: a checkpoint may commit after acknowledge's first read. Its
            // writer takes these same row locks, so a fresh proof read here either wins or is fenced by our update.
            if (current.get().state.equals("DISPATCHED")) {
                InboxMessage prior = signal(c,claim.wait).orElseThrow(() -> new IllegalStateException("dispatch receipt absent"));
                if (proof.consumed(claim.wait,prior)) return Optional.empty();
            }
            if (!status.equals("WAITING")) {
                if (!status.equals("RUNNING") && !status.equals("STARTING")) dispose(c, claim.wait, "CANCELLED");
                return Optional.empty();
            }
            Instant now = databaseNow(c); // Sample AFTER both row locks, including any wait for cancellation.
            if (!current.get().deadline.isAfter(now) || (registry.deadline != null && !registry.deadline.isAfter(now))) {
                dispose(c, claim.wait, "EXPIRED"); return Optional.empty();
            }
            Optional<InboxMessage> message = signal(c, claim.wait);
            if (message.isEmpty()) return Optional.empty();
            try (var s = c.prepareStatement("""
                    update inbox.delivery set state='DISPATCHED',attempts=attempts+1,reserved_at=clock_timestamp()
                    where tenant=? and instance_id=? and wait_key=? returning attempts,deadline
                    """)) {
                route(s, claim.wait);
                try (var r = s.executeQuery()) {
                    r.next(); return Optional.of(new Reserved(claim.wait, message.get(), r.getInt(1), r.getTimestamp(2).toInstant()));
                }
            }
        });
        reserved.ifPresent(r -> record(r, "event.dispatch.attempt"));
        if (reserved.isEmpty()) acknowledge(claim,proof);
        return reserved;
    }

    /** The immutable atomic proof establishes that the checkpoint won its cancellation/attempt race. */
    public Acknowledgement acknowledge(Claim claim, InboxCheckpointProof proof) {
        requireClaim(claim); Objects.requireNonNull(proof);
        Optional<Reserved> pending = inClaim(claim, c -> {
            instance(c, claim.wait); var row = current(c, claim.wait);
            if (row.isEmpty() || !row.get().state.equals("DISPATCHED")) return Optional.empty();
            return signal(c, claim.wait).map(m -> new Reserved(claim.wait, m, row.get().attempts, row.get().deadline));
        });
        if (pending.isEmpty()) return Acknowledgement.NO_RESERVATION;
        if (!proof.consumed(claim.wait, pending.get().receipt)) return Acknowledgement.NO_PROOF;
        record(pending.get(), "event.checkpoint.observed");
        return inClaim(claim, c -> {
            instance(c, claim.wait); var row = current(c, claim.wait);
            if (row.isEmpty() || !row.get().state.equals("DISPATCHED")) return Acknowledgement.NO_RESERVATION;
            // The instance may complete or be cancelled AFTER the successful checkpoint. That cannot turn actual
            // consumption into a discard. The proof writer itself checked cancellation under this lock at commit.
            // The reservation accepted before its deadline. A delayed checkpoint acknowledgement must not extend
            // the deadline or replay into a different occurrence. The proof must identify this exact receipt.
            try (var s = c.prepareStatement("update inbox.delivery set state='ACKNOWLEDGED',acknowledged_at=clock_timestamp() "
                    + "where tenant=? and instance_id=? and wait_key=?")) { route(s, claim.wait); s.executeUpdate(); }
            return Acknowledgement.ACKNOWLEDGED;
        });
    }

    private record Current(String state, int attempts, Instant deadline) { }
    private static Optional<Current> current(Connection c, InboxWait wait) throws SQLException {
        try (var s = c.prepareStatement("select state,attempts,deadline from inbox.delivery "
                + "where tenant=? and instance_id=? and wait_key=? for update")) {
            route(s, wait);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(new Current(r.getString(1), r.getInt(2),
                    r.getTimestamp(3).toInstant())) : Optional.empty(); }
        }
    }
    private record Registry(String status, Instant deadline) { }
    private static Registry instance(Connection c, InboxWait wait) throws SQLException {
        try (var s = c.prepareStatement("select status,wait_deadline,definition_id,definition_version,engine_instance_id "
                + "from task.instance where tenant=? and id=? for update")) {
            s.setString(1, wait.tenant()); s.setString(2, wait.instanceId().value());
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new NoSuchElementException("instance absent in tenant");
                if (!r.getString(3).equals(wait.namespace()+"/"+wait.definition()) || !r.getString(4).equals(wait.version())
                        || (r.getString(5) != null && !r.getString(5).equals(wait.engineInstanceId()))) {
                    throw new IllegalStateException("wait does not name the registry's pinned workflow/engine instance");
                }
                Timestamp deadline = r.getTimestamp(2);
                return new Registry(r.getString(1), deadline == null ? null : deadline.toInstant());
            }
        }
    }
    private static Instant databaseNow(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("select clock_timestamp()")) {
            r.next(); return r.getTimestamp(1).toInstant();
        }
    }
    private static Optional<InboxMessage> signal(Connection c, InboxWait wait) throws SQLException {
        try (var s = c.prepareStatement("""
                select m.* from inbox.signal s join inbox.message m
                on m.tenant=s.tenant and m.source=s.source and m.event_id=s.event_id
                where s.tenant=? and s.instance_id=? and s.wait_key=?
                """)) {
            route(s, wait);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var m = new InboxMessage(r.getString("tenant"),r.getString("source"),r.getString("event_id"),
                        r.getString("type"),wait.instanceId(),r.getString("correlation_key"),r.getString("data_json"));
                if (!m.type().equals(wait.eventType()) || !m.correlationKey().equals(wait.key())) {
                    throw new IllegalStateException("signal does not match immutable wait contract");
                }
                return Optional.of(m);
            }
        }
    }
    private static boolean terminal(String state) { return Set.of("ACKNOWLEDGED","CANCELLED","EXPIRED").contains(state); }
    private static void dispose(Connection c, InboxWait wait, String state) throws SQLException {
        try (var s = c.prepareStatement("update inbox.delivery set state=? where tenant=? and instance_id=? and wait_key=?")) {
            s.setString(1,state); s.setString(2,wait.tenant()); s.setString(3,wait.instanceId().value()); s.setString(4,wait.key()); s.executeUpdate();
        }
    }
    private static void route(PreparedStatement s, InboxWait wait) throws SQLException {
        s.setString(1,wait.tenant()); s.setString(2,wait.instanceId().value()); s.setString(3,wait.key());
    }
    private void record(Reserved r, String action) {
        audit.record(AuditDraft.of(UlidGenerator.shared().next(), Instant.now(), RECOVERY, action, "event-delivery",
                r.occurrence.digest(), Map.of(AuditKeys.TENANT,r.occurrence.tenant(),AuditKeys.INSTANCE,r.occurrence.instanceId().value(),
                        "event.wait",r.occurrence.key(),"event.receipt",r.receipt.fingerprint(),"event.attempt",Integer.toString(r.attempt))));
    }
    private void requireClaim(Claim claim) {
        Objects.requireNonNull(claim);
        if (claim.owner != this || claim.closed.get()) throw new IllegalStateException("live dispatch claim required");
    }

    /** Reservation writes use the lock-owning session itself; loss of that session cannot authorize a new write. */
    private <T> T inClaim(Claim claim, Transactions.Work<T> work) {
        synchronized (claim) {
            requireClaim(claim);
            Connection c = claim.connection;
            try {
                c.setAutoCommit(false);
                T value = work.run(c); c.commit(); c.setAutoCommit(true); return value;
            } catch (SQLException failure) {
                claim.closed.set(true); discard(c, failure);
                try { c.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw new StoreUnavailableException("dispatch claim transaction failed",failure);
            } catch (RuntimeException | Error failure) {
                try { c.rollback(); c.setAutoCommit(true); }
                catch (SQLException rollback) {
                    claim.closed.set(true); discard(c, rollback); failure.addSuppressed(rollback);
                    try { c.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                }
                throw failure;
            }
        }
    }

    private static void discard(Connection c, SQLException failure) {
        try { c.abort(Runnable::run); } catch (SQLException abort) { failure.addSuppressed(abort); }
    }

    public static final class Claim implements AutoCloseable {
        private final JdbcInboxDispatch owner; private final Connection connection; private final InboxWait wait;
        private final int hash; private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean attempted = new AtomicBoolean();
        private Claim(JdbcInboxDispatch owner, Connection connection, InboxWait wait, int hash) {
            this.owner=owner; this.connection=connection; this.wait=wait; this.hash=hash;
        }
        @Override public synchronized void close() {
            if (!closed.compareAndSet(false,true)) return;
            try (var s = connection.prepareStatement("select pg_advisory_unlock(?,?)")) {
                s.setInt(1,LOCK_CLASS); s.setInt(2,hash); s.executeQuery().close();
            } catch (SQLException e) { discard(connection,e); throw new StoreUnavailableException("dispatch claim release failed",e); }
            finally { try { connection.close(); } catch (SQLException e) { throw new StoreUnavailableException("dispatch connection close failed",e); } }
        }
    }
}
