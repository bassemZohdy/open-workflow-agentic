package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectCommand;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectKey;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ManualEffect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectLedger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Durable ledger. Each operation commits on its own connection; an ambient caller transaction is suspended at the
 * CDI boundary so rolling back business work cannot erase a dispatched intent. Network calls never run inside these
 * transactions. Session advisory locks coordinate cooperating executors across processes; a busy key fails promptly.
 * The internal persistence API does not authorize callers and is not exposed as an HTTP endpoint.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcEffectLedger implements EffectLedger {
    private static final String WHERE = " where thread_name = ? and node_id = ? and idempotency_key = ?";
    private static final String SELECT = "select * from effect.ledger";
    // PostgreSQL's two-int advisory namespace is disjoint from the bigint audit/lifecycle locks.
    private static final int LOCK_CLASS = 0x4F414546; // OAEF
    private final DataSource dataSource;

    public record Prepared(Effect effect, boolean attempted, Instant replayUntil, Instant createdAt) { }

    @Inject
    public JdbcEffectLedger(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override
    public Effect recordIntent(Effect intent) {
        Objects.requireNonNull(intent, "intent");
        if (intent.state() != EffectState.INTENDED) {
            throw new IllegalArgumentException("an intent must be INTENDED");
        }
        return Transactions.inTransaction(dataSource, c -> insert(c, intent, null, null, "EFFECT").effect());
    }

    /** Fingerprint is bound atomically on first creation and cannot be changed on recovery. */
    public Prepared prepare(EffectCommand command, Duration replayWindow, Instant now) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(replayWindow, "replayWindow");
        if (replayWindow.isNegative() || replayWindow.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("replay window must be between zero and 30 days");
        }
        Instant until = replayWindow.isZero() ? null : now.plus(replayWindow);
        EffectKey key = command.key();
        Effect intent = new Effect(key.threadId(), key.nodeId(), key.idempotencyKey(), command.target(),
                EffectState.INTENDED, null, now);
        return Transactions.inTransaction(dataSource, c -> insert(c, intent, command.requestFingerprint(), until, "EFFECT"));
    }

    /** A compensation intent and its original link commit together, before any refund can be sent. */
    public Prepared prepareCompensation(EffectKey original, EffectCommand command, Duration window, Instant now) {
        if (!original.threadId().equals(command.key().threadId()) || original.equals(command.key())) {
            throw new IllegalArgumentException("compensation requires another key in the original scope");
        }
        if (window.isNegative() || window.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("replay window must be between zero and 30 days");
        }
        EffectKey key = command.key();
        Effect intent = new Effect(key.threadId(), key.nodeId(), key.idempotencyKey(), command.target(),
                EffectState.INTENDED, null, now);
        return Transactions.inTransaction(dataSource, c -> {
            if (locked(c, original).effect().state() == EffectState.INTENDED) {
                throw new IllegalStateException("resolve original outcome first");
            }
            Prepared result = insert(c, intent, command.requestFingerprint(),
                    window.isZero() ? null : now.plus(window), "COMPENSATION");
            linkCompensation(c, original, key);
            return result;
        });
    }

    private Prepared insert(Connection c, Effect intent, String fingerprint, Instant replayUntil, String purpose) throws SQLException {
        int inserted;
        try (PreparedStatement s = c.prepareStatement("""
                insert into effect.ledger (thread_name, node_id, idempotency_key, target, state, detail, recorded_at,
                    request_fingerprint, replay_until, purpose)
                values (?, ?, ?, ?, 'INTENDED', ?, ?, ?, ?, ?) on conflict do nothing
                """)) {
            bind(s, intent.key());
            s.setString(4, intent.target()); s.setString(5, intent.detail());
            s.setTimestamp(6, Timestamp.from(intent.recordedAt())); s.setString(7, fingerprint);
            s.setTimestamp(8, replayUntil == null ? null : Timestamp.from(replayUntil));
            s.setString(9, purpose);
            inserted = s.executeUpdate();
        }
        // Separate statement: at READ COMMITTED it sees a concurrent winner even when INSERT's snapshot did not.
        try (PreparedStatement s = c.prepareStatement(SELECT + WHERE + " for update")) {
            bind(s, intent.key());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("committed effect intent is missing");
                Prepared stored = prepared(r);
                if (fingerprint != null && !purpose.equals(r.getString("purpose"))) {
                    throw new IllegalStateException("ordinary effect and compensation identities cannot be reused");
                }
                if (!stored.effect().target().equals(intent.target())) {
                    throw new IllegalStateException("effect key is already bound to another target");
                }
                if (fingerprint != null && !fingerprint.equals(r.getString("request_fingerprint"))) {
                    throw new IllegalStateException("effect key is bound to another or unverified request");
                }
                if (inserted == 1) journal(c, intent.key(), null, EffectState.INTENDED, "effect.intended");
                return stored;
            }
        }
    }

    @Override
    public Effect recordOutcome(EffectKey key, EffectState outcome, String detail) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(outcome, "outcome");
        if (detail != null && detail.length() > Effect.MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("effect detail is too long");
        }
        return Transactions.inTransaction(dataSource, c -> {
            Effect current = locked(c, key).effect();
            if (current.state() == outcome) return current;
            if (!current.state().canMoveTo(outcome)) throw new IllegalStateException("illegal effect transition");
            try (PreparedStatement s = c.prepareStatement("update effect.ledger set state = ?, detail = ?, "
                    + "recorded_at = clock_timestamp()" + WHERE + " returning *")) {
                s.setString(1, outcome.name()); s.setString(2, detail); bind(s, key, 3);
                try (ResultSet r = s.executeQuery()) {
                    r.next(); Effect result = read(r);
                    journal(c, key, current.state(), outcome, "effect.outcome");
                    try (PreparedStatement close = c.prepareStatement("update effect.manual_resolution "
                            + "set closed_at = clock_timestamp()" + WHERE + " and closed_at is null")) {
                        bind(close, key); close.executeUpdate();
                    }
                    return result;
                }
            }
        });
    }

    public Optional<Effect> find(EffectKey key) {
        Objects.requireNonNull(key, "key");
        return Transactions.inTransaction(dataSource, c -> {
            try (PreparedStatement s = c.prepareStatement(SELECT + WHERE)) {
                bind(s, key);
                try (ResultSet r = s.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
            }
        });
    }

    @Override
    public List<Effect> findUnresolved(ThreadId threadId) {
        Objects.requireNonNull(threadId, "threadId");
        return Transactions.inTransaction(dataSource, c -> {
            try (PreparedStatement s = c.prepareStatement(SELECT + " where thread_name = ? and state = 'INTENDED' "
                    + "order by created_at, node_id, idempotency_key")) {
                s.setString(1, threadId.value());
                List<Effect> result = new ArrayList<>();
                try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(read(r)); }
                return List.copyOf(result);
            }
        });
    }

    /** Committed before dispatch. On a crash, true means a call MAY have reached the target. */
    public void markAttempted(EffectKey key) {
        Transactions.inTransaction(dataSource, c -> {
            Prepared current = locked(c, key);
            if (current.effect().state() != EffectState.INTENDED) throw new IllegalStateException("effect is resolved");
            if (!current.attempted()) {
                try (PreparedStatement s = c.prepareStatement("update effect.ledger set attempted = true" + WHERE)) {
                    bind(s, key); s.executeUpdate();
                }
                journal(c, key, EffectState.INTENDED, EffectState.INTENDED, "effect.dispatch");
            }
            return null;
        });
    }

    public void requireManualResolution(EffectKey key, String reason) {
        Checks.requireText("reason", reason, 64);
        Transactions.inTransaction(dataSource, c -> {
            Effect current = locked(c, key).effect();
            try (PreparedStatement s = c.prepareStatement("""
                    insert into effect.manual_resolution (thread_name, node_id, idempotency_key, reason)
                    values (?, ?, ?, ?) on conflict (thread_name, node_id, idempotency_key) do update
                    set reason = excluded.reason, last_seen = clock_timestamp(), closed_at = null
                    """)) {
                bind(s, key); s.setString(4, reason); s.executeUpdate();
            }
            journal(c, key, current.state(), current.state(), reason);
            return null;
        });
    }

    /** Bounded, thread-scoped internal queue; public operator access requires the verified caller/PEP integration. */
    public List<ManualEffect> manualQueue(ThreadId thread, int limit) {
        Objects.requireNonNull(thread, "thread"); Checks.requireInRange("limit", limit, 1, 200);
        return Transactions.inTransaction(dataSource, c -> {
            try (PreparedStatement s = c.prepareStatement("select * from effect.manual_resolution "
                    + "where thread_name = ? and closed_at is null order by first_seen, node_id, idempotency_key limit ?")) {
                s.setString(1, thread.value()); s.setInt(2, limit);
                List<ManualEffect> result = new ArrayList<>();
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) result.add(new ManualEffect(key(r), r.getString("reason"),
                            r.getTimestamp("first_seen").toInstant(), r.getTimestamp("last_seen").toInstant()));
                }
                return List.copyOf(result);
            }
        });
    }

    /** Links exactly one persisted compensation to an original. Both effects share the original scope. */
    private static void linkCompensation(Connection c, EffectKey original, EffectKey compensation) throws SQLException {
            try (PreparedStatement s = c.prepareStatement("""
                    insert into effect.compensation (thread_name, node_id, idempotency_key, compensation_node,
                        compensation_key) values (?, ?, ?, ?, ?) on conflict do nothing
                    """)) {
                bind(s, original); s.setString(4, compensation.nodeId());
                s.setString(5, compensation.idempotencyKey()); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("select compensation_node, compensation_key "
                    + "from effect.compensation" + WHERE)) {
                bind(s, original);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next() || !compensation.nodeId().equals(r.getString(1))
                            || !compensation.idempotencyKey().equals(r.getString(2))) {
                        throw new IllegalStateException("original is bound to another compensation");
                    }
                }
            }
    }

    /** Session lock, not a network-spanning transaction. Competing workers fail without waiting. */
    public <T> T exclusively(EffectKey key, Supplier<T> work) {
        byte[] digest = java.util.HexFormat.of().parseHex(CanonicalEncoder.sha256Hex(
                new CanonicalEncoder("oa.effect.lock.v1").text(key.threadId().value())
                        .text(key.nodeId()).text(key.idempotencyKey()).toBytes()));
        int lock = ByteBuffer.wrap(digest).getInt();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement s = c.prepareStatement("select pg_try_advisory_lock(?, ?)")) {
                s.setInt(1, LOCK_CLASS); s.setInt(2, lock);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    if (!r.getBoolean(1)) throw new EffectBusyException();
                }
            }
            try { return work.get(); }
            finally {
                try (PreparedStatement s = c.prepareStatement("select pg_advisory_unlock(?, ?)")) {
                    s.setInt(1, LOCK_CLASS); s.setInt(2, lock); s.execute();
                }
            }
        } catch (SQLException e) { throw new StoreUnavailableException("effect claim database failed", e); }
    }

    public static class EffectBusyException extends RuntimeException {
        public EffectBusyException() { super("effect is being handled by another worker; retry later with the same key"); }
    }

    private static Prepared locked(Connection c, EffectKey key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(SELECT + WHERE + " for update")) {
            bind(s, key);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new NoSuchElementException("effect intent does not exist");
                return prepared(r);
            }
        }
    }

    private static Prepared prepared(ResultSet r) throws SQLException {
        Timestamp until = r.getTimestamp("replay_until");
        return new Prepared(read(r), r.getBoolean("attempted"), until == null ? null : until.toInstant(),
                r.getTimestamp("created_at").toInstant());
    }

    private static Effect read(ResultSet r) throws SQLException {
        EffectKey key = key(r);
        return new Effect(key.threadId(), key.nodeId(), key.idempotencyKey(), r.getString("target"),
                EffectState.valueOf(r.getString("state")), r.getString("detail"),
                r.getTimestamp("recorded_at").toInstant());
    }

    private static EffectKey key(ResultSet r) throws SQLException {
        return new EffectKey(ThreadId.parse(r.getString("thread_name")), r.getString("node_id"),
                r.getString("idempotency_key"));
    }

    private static void bind(PreparedStatement s, EffectKey key) throws SQLException { bind(s, key, 1); }
    private static void bind(PreparedStatement s, EffectKey key, int offset) throws SQLException {
        s.setString(offset, key.threadId().value()); s.setString(offset + 1, key.nodeId());
        s.setString(offset + 2, key.idempotencyKey());
    }

    private static void journal(Connection c, EffectKey key, EffectState from, EffectState to, String reason)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                insert into effect.event (thread_name, node_id, idempotency_key, from_state, to_state, reason)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            bind(s, key); s.setString(4, from == null ? null : from.name());
            s.setString(5, to.name()); s.setString(6, reason); s.executeUpdate();
        }
    }
}
