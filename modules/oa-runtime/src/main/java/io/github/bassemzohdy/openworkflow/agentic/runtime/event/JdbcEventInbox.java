package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import javax.sql.DataSource;

/**
 * Persist-before-wake inbox component. No public ingress or network dispatch is exposed. Matching persists a wake
 * intent under the same instance row lock as lifecycle cancellation; consumers must integrate that durable intent
 * with a deduplicating wait adapter before waking Flow. This component alone does not claim end-to-end delivery.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcEventInbox {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DataSource dataSource;
    private final Clock clock;

    public record Received(InboxMessage message, Instant retainUntil, boolean created) { }
    public record Candidate(long sequence, InboxMessage message) { }

    @Inject
    public JdbcEventInbox(DataSource dataSource) { this(dataSource, Clock.systemUTC()); }
    public JdbcEventInbox(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Deletes messages whose retention ended more than {@code grace} ago and that nothing refers to: a message that a
     * wait signal or a listen group item points at is part of that wait's record and is kept. A stored event can no
     * longer match once its retention ends (reads filter on it), so this only reclaims space. The duplicate-receipt
     * window is the retention: an event id redelivered after its message was purged is a new receipt.
     *
     * <p>Bounded and idempotent: at most {@code limit} rows per call, oldest first, safe to run on several replicas at
     * once (a row already deleted by another run is simply not deleted twice).
     *
     * @return the number of messages deleted
     */
    public int purgeExpired(Duration grace, int limit) {
        Objects.requireNonNull(grace, "grace");
        if (grace.isNegative()) throw new IllegalArgumentException("grace must not be negative");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be 1..10000");
        Instant cutoff = clock.instant().minus(grace);
        return Transactions.inTransaction(dataSource, c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    delete from inbox.message where (tenant, source, event_id) in (
                        select m.tenant, m.source, m.event_id from inbox.message m
                         where m.retain_until < ?
                           and not exists (select 1 from inbox.signal g where g.tenant = m.tenant
                                            and g.source = m.source and g.event_id = m.event_id)
                           and not exists (select 1 from inbox.listen_item i where i.tenant = m.tenant
                                            and i.source = m.source and i.event_id = m.event_id)
                         order by m.retain_until limit ?
                         for update of m skip locked)
                    """)) {
                s.setTimestamp(1, java.sql.Timestamp.from(cutoff));
                s.setInt(2, limit);
                return s.executeUpdate();
            }
        });
    }

    /** Duplicate receipt never changes the original payload, route or retention deadline. */
    public Received receive(InboxMessage message, Instant retainUntil) {
        Objects.requireNonNull(message); Objects.requireNonNull(retainUntil);
        Instant now = clock.instant();
        if (!retainUntil.isAfter(now) || retainUntil.isAfter(now.plus(Duration.ofDays(30)))) {
            throw new IllegalArgumentException("retention must be positive and at most 30 days");
        }
        try {
            var data = JSON.readTree(message.dataJson());
            if (data == null || data.isMissingNode()) throw new IllegalArgumentException("event data must be JSON");
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("event data must be JSON", invalid); }
        return Transactions.inTransaction(dataSource, c -> {
            // Locks the target instance before the inbox, including on duplicate receipt.
            status(c, message.tenant(), message.instanceId());
            int inserted;
            try (PreparedStatement s = c.prepareStatement("""
                    insert into inbox.message (tenant, source, event_id, type, instance_id, correlation_key,
                        data_json, fingerprint, retain_until) values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing
                    """)) {
                identity(s, message); s.setString(4, message.type()); s.setString(5, message.instanceId().value());
                s.setString(6, message.correlationKey()); s.setString(7, message.dataJson());
                s.setString(8, message.fingerprint()); s.setTimestamp(9, Timestamp.from(retainUntil));
                inserted = s.executeUpdate();
            }
            // New statement at READ COMMITTED sees a winner that INSERT's snapshot could not see.
            try (PreparedStatement s = c.prepareStatement("select fingerprint, retain_until from inbox.message "
                    + "where tenant=? and source=? and event_id=? for update")) {
                identity(s, message);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("committed inbox message missing");
                    if (!message.fingerprint().equals(r.getString(1))) {
                        throw new IllegalStateException("event source/id is bound to different content or routing");
                    }
                    return new Received(message, r.getTimestamp(2).toInstant(), inserted == 1);
                }
            }
        });
    }

    /**
     * Find an existing durable signal or match the earliest received eligible event to this logical wait.
     * Waiting status and the persisted wait deadline are checked under the instance lock; cancellation/terminal
     * states win if committed first. Event producer timestamps do not establish causal ordering.
     */
    public Optional<InboxMessage> match(String tenant, Ulid instance, String waitKey, String type) {
        Checks.requireText("tenant", tenant, 256); Objects.requireNonNull(instance);
        Checks.requireText("waitKey", waitKey, 256); Checks.requireText("type", type, 256);
        return Transactions.inTransaction(dataSource, c -> {
            if (!status(c, tenant, instance).equals("WAITING")) return Optional.empty();
            try (PreparedStatement s = c.prepareStatement("""
                    select m.* from inbox.signal d join inbox.message m
                    on m.tenant=d.tenant and m.source=d.source and m.event_id=d.event_id
                    where d.tenant=? and d.instance_id=? and d.wait_key=?
                    """)) {
                route(s, tenant, instance, waitKey);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        InboxMessage existing = read(r);
                        if (!existing.type().equals(type)) throw new IllegalStateException("wait key is bound to another type");
                        return Optional.of(existing);
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement("""
                    select m.* from inbox.message m where m.tenant=? and m.instance_id=?
                    and m.correlation_key=? and m.type=? and m.retain_until > clock_timestamp()
                    and not exists (select 1 from inbox.signal d
                        where d.tenant=m.tenant and d.source=m.source and d.event_id=m.event_id)
                    order by m.sequence limit 1 for update of m
                    """)) {
                route(s, tenant, instance, waitKey); s.setString(4, type);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    InboxMessage message = read(r);
                    try (PreparedStatement insert = c.prepareStatement("""
                            insert into inbox.signal (tenant, instance_id, wait_key, source, event_id)
                            values (?, ?, ?, ?, ?)
                            """)) {
                        route(insert, tenant, instance, waitKey);
                        insert.setString(4, message.source()); insert.setString(5, message.eventId());
                        insert.executeUpdate();
                    }
                    return Optional.of(message);
                }
            }
        });
    }

    /**
     * Bounded read-only candidates for SDK predicate evaluation OUTSIDE a DB transaction. The public correlation
     * route is independent of the internal task occurrence. A snapshot conveys no permission to invoke a callback.
     * Callers can continue after the last sequence, and rescan after correlation state changes.
     */
    public List<Candidate> candidates(InboxWait wait, long afterSequence, int limit) {
        Objects.requireNonNull(wait);
        if (afterSequence < 0 || limit < 1 || limit > 128) throw new IllegalArgumentException("invalid candidate page");
        return Transactions.inTransaction(dataSource,c -> {
            List<Candidate> found = new ArrayList<>();
            try(var s=c.prepareStatement("""
                    select m.* from inbox.message m where m.tenant=? and m.instance_id=?
                    and m.correlation_key=? and m.type=? and m.sequence>? and m.retain_until>clock_timestamp()
                    and not exists(select 1 from inbox.signal d where d.tenant=m.tenant and d.source=m.source and d.event_id=m.event_id)
                    order by m.sequence limit ?
                    """)) {
                route(s,wait.tenant(),wait.instanceId(),wait.route()); s.setString(4,wait.eventType());
                s.setLong(5,afterSequence); s.setInt(6,limit);
                try(var r=s.executeQuery()) { while(r.next()) found.add(new Candidate(r.getLong("sequence"),read(r))); }
            }
            return List.copyOf(found);
        });
    }

    /** Historical binding snapshot for predicate re-evaluation on restore. Dispatch still requires a live reservation. */
    public Optional<InboxMessage> boundSignal(InboxWait wait) {
        Objects.requireNonNull(wait);
        return Transactions.inTransaction(dataSource,c -> {
            try(var s=c.prepareStatement("select m.* from inbox.delivery d join inbox.signal s using(tenant,instance_id,wait_key) "
                    + "join inbox.message m on m.tenant=s.tenant and m.source=s.source and m.event_id=s.event_id "
                    + "where d.tenant=? and d.instance_id=? and d.wait_key=? and d.identity_digest=?")) {
                route(s,wait.tenant(),wait.instanceId(),wait.key()); s.setString(4,wait.digest());
                try(var r=s.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    InboxMessage bound=read(r);
                    if (!bound.type().equals(wait.eventType()) || !bound.correlationKey().equals(wait.route())) {
                        throw new IllegalStateException("bound receipt is outside the registered producer route");
                    }
                    return Optional.of(bound);
                }
            }
        });
    }

    /**
     * Bind a predicate-approved candidate to the registered logical occurrence. All scope/content/retention,
     * cancellation and BOTH deadlines are rechecked under locks. A losing/stale snapshot remains non-authoritative.
     */
    public Optional<InboxMessage> matchCandidate(InboxWait wait, InboxMessage candidate) {
        requireCandidate(wait,candidate);
        return Transactions.inTransaction(dataSource,c -> matchCandidate(c,wait,candidate));
    }

    /** Shares the owning task's transaction; predicates were evaluated before entry, never under these locks. */
    Optional<InboxMessage> matchCandidate(Connection c,InboxWait wait,InboxMessage candidate) throws SQLException {
        requireCandidate(wait,candidate);
            if (!status(c,wait.tenant(),wait.instanceId()).equals("WAITING")) return Optional.empty();
            try(var s=c.prepareStatement("select identity_digest,state,deadline from inbox.delivery "
                    + "where tenant=? and instance_id=? and wait_key=? for update")) {
                route(s,wait.tenant(),wait.instanceId(),wait.key());
                try(var r=s.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("logical wait is not registered");
                    if (!r.getString(1).equals(wait.digest())) throw new IllegalStateException("logical wait identity changed");
                    if (!r.getString(2).equals("REGISTERED") && !r.getString(2).equals("DISPATCHED")) return Optional.empty();
                    // Sample after acquiring both locks, not at statement start before a possible lock wait.
                    try(var time=c.createStatement(); var now=time.executeQuery("select clock_timestamp()")) {
                        now.next(); if (!r.getTimestamp(3).toInstant().isAfter(now.getTimestamp(1).toInstant())) return Optional.empty();
                    }
                }
            }
            try(var s=c.prepareStatement("select m.* from inbox.signal d join inbox.message m "
                    + "on m.tenant=d.tenant and m.source=d.source and m.event_id=d.event_id "
                    + "where d.tenant=? and d.instance_id=? and d.wait_key=?")) {
                route(s,wait.tenant(),wait.instanceId(),wait.key());
                try(var r=s.executeQuery()) {
                    if (r.next()) {
                        InboxMessage bound = read(r);
                        if (!bound.type().equals(wait.eventType()) || !bound.correlationKey().equals(wait.route())) {
                            throw new IllegalStateException("logical wait is bound outside its producer route");
                        }
                        // Never substitute a different receipt AFTER the caller evaluated a candidate's predicate.
                        // Restore first loads boundSignal and evaluates that exact receipt instead.
                        return bound.fingerprint().equals(candidate.fingerprint()) ? Optional.of(bound) : Optional.empty();
                    }
                }
            }
            try(var s=c.prepareStatement("select m.* from inbox.message m where m.tenant=? and m.source=? and m.event_id=? "
                    + "and m.instance_id=? and m.correlation_key=? and m.type=? and m.retain_until>clock_timestamp() "
                    + "and not exists(select 1 from inbox.signal d where d.tenant=m.tenant and d.source=m.source and d.event_id=m.event_id) "
                    + "for update of m")) {
                identity(s,candidate); s.setString(4,wait.instanceId().value()); s.setString(5,wait.route()); s.setString(6,wait.eventType());
                try(var r=s.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    InboxMessage actual = read(r);
                    if (!actual.fingerprint().equals(candidate.fingerprint())) throw new IllegalStateException("candidate content changed");
                    try(var time=c.createStatement(); var now=time.executeQuery("select clock_timestamp()")) {
                        now.next();
                        if (!r.getTimestamp("retain_until").toInstant().isAfter(now.getTimestamp(1).toInstant())) return Optional.empty();
                    }
                    try(var insert=c.prepareStatement("insert into inbox.signal(tenant,instance_id,wait_key,source,event_id) values(?,?,?,?,?)")) {
                        route(insert,wait.tenant(),wait.instanceId(),wait.key());
                        insert.setString(4,actual.source()); insert.setString(5,actual.eventId()); insert.executeUpdate();
                    }
                    return Optional.of(actual);
                }
            }
    }

    private static void requireCandidate(InboxWait wait,InboxMessage candidate) {
        Objects.requireNonNull(wait);Objects.requireNonNull(candidate);
        if(!candidate.tenant().equals(wait.tenant()) || !candidate.instanceId().equals(wait.instanceId())
                || !candidate.correlationKey().equals(wait.route()) || !candidate.type().equals(wait.eventType()))
            throw new IllegalArgumentException("candidate is outside this wait's producer route");
    }
    private static String status(Connection c, String tenant, Ulid instance) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select status, wait_deadline from task.instance "
                + "where tenant=? and id=? for update")) {
            s.setString(1, tenant); s.setString(2, instance.value());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new NoSuchElementException("instance is absent in this tenant");
                // Evaluate deadlines using database time in the statement that authorizes matching.
                Timestamp deadline = r.getTimestamp(2);
                if (deadline != null) {
                    try (var time = c.createStatement(); var now = time.executeQuery("select clock_timestamp()")) {
                        now.next();
                        if (!deadline.toInstant().isAfter(now.getTimestamp(1).toInstant())) return "EXPIRED";
                    }
                }
                return r.getString(1);
            }
        }
    }

    private static void identity(PreparedStatement s, InboxMessage message) throws SQLException {
        s.setString(1, message.tenant()); s.setString(2, message.source()); s.setString(3, message.eventId());
    }
    private static void route(PreparedStatement s, String tenant, Ulid instance, String key) throws SQLException {
        s.setString(1, tenant); s.setString(2, instance.value()); s.setString(3, key);
    }
    private static InboxMessage read(ResultSet r) throws SQLException {
        return new InboxMessage(r.getString("tenant"), r.getString("source"), r.getString("event_id"),
                r.getString("type"), Ulid.parse(r.getString("instance_id")), r.getString("correlation_key"),
                r.getString("data_json"));
    }
}
