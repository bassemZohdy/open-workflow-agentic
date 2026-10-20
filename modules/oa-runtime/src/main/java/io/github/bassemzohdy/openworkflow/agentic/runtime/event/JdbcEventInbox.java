package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
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

    @Inject
    public JdbcEventInbox(DataSource dataSource) { this(dataSource, Clock.systemUTC()); }
    public JdbcEventInbox(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
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
