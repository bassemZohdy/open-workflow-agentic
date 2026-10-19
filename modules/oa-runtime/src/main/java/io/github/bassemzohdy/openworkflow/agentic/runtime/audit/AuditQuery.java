package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Read-only queries over the audit log (M15). Pages are keyset pages on {@code (chain_id, seq)}, the order in which
 * each chain is committed. That order is stable under concurrent appends: a new event only ever gets a higher
 * sequence in its own chain, so a page never shifts. The cursor is opaque to clients.
 *
 * <p>This class does not check access. {@link AuditAccess} does, in the resource.
 */
public final class AuditQuery {

    /**
     * Filters. Null fields are not applied. {@code from} is inclusive and {@code to} exclusive, on {@code occurred_at}.
     */
    public record Filter(String instanceId, String correlationId, String chainId, String type, Instant from,
                         Instant to) {

        public static final Filter NONE = new Filter(null, null, null, null, null, null);
    }

    /** One page: the events, and the cursor for the next page or null at the end. */
    public record Page(List<AuditEvent> items, String nextCursor) {
    }

    private final DataSource dataSource;

    public AuditQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * One page of events that match the filter.
     *
     * @throws IllegalArgumentException when the cursor was not produced by this class
     */
    public Page find(Filter filter, PageRequest page) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        StringBuilder sql = new StringBuilder("select " + AuditRows.EVENT_COLUMNS + " from audit.event where true");
        List<Object> params = new ArrayList<>();
        if (filter.instanceId() != null) {
            sql.append(" and instance_id = ?");
            params.add(filter.instanceId());
        }
        if (filter.correlationId() != null) {
            sql.append(" and correlation_id = ?");
            params.add(filter.correlationId());
        }
        if (filter.chainId() != null) {
            sql.append(" and chain_id = ?");
            params.add(filter.chainId());
        }
        if (filter.type() != null) {
            sql.append(" and event_type = ?");
            params.add(filter.type());
        }
        if (filter.from() != null) {
            sql.append(" and occurred_at >= ?");
            params.add(AuditRows.offset(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" and occurred_at < ?");
            params.add(AuditRows.offset(filter.to()));
        }
        if (page.cursor() != null) {
            Cursor after = Cursor.decode(page.cursor());
            sql.append(" and (chain_id, seq) > (?, ?)");
            params.add(after.chainId());
            params.add(after.sequence());
        }
        sql.append(" order by chain_id, seq limit ?");
        params.add(page.limit() + 1L);

        List<AuditEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(AuditRows.event(rs));
                }
            }
        } catch (SQLException e) {
            throw new AuditStoreException("audit events could not be queried", e);
        }

        if (events.size() <= page.limit()) {
            return new Page(events, null);
        }
        List<AuditEvent> items = events.subList(0, page.limit());
        AuditEvent last = items.get(items.size() - 1);
        return new Page(List.copyOf(items), Cursor.of(chainOf(last), last.sequence()).encode());
    }

    /** One event by its id, or empty. */
    public Optional<AuditEvent> byId(Ulid eventId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select " + AuditRows.EVENT_COLUMNS + " from audit.event where event_id = ?")) {
            statement.setString(1, eventId.value());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(AuditRows.event(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new AuditStoreException("audit event could not be read", e);
        }
    }

    /** The chain of an event, from its attributes; the default chain when the attribute is absent. */
    static String chainOf(AuditEvent event) {
        return event.attributes().getOrDefault(AuditKeys.CHAIN, AuditKeys.DEFAULT_CHAIN);
    }

    /** An opaque position in the (chain, sequence) order. */
    record Cursor(String chainId, long sequence) {

        static Cursor of(String chainId, long sequence) {
            return new Cursor(chainId, sequence);
        }

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((sequence + ":" + chainId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String text) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
                int colon = raw.indexOf(':');
                if (colon < 1) {
                    throw new IllegalArgumentException("cursor is not valid");
                }
                long sequence = Long.parseLong(raw.substring(0, colon));
                String chain = AuditKeys.requireChainId(raw.substring(colon + 1));
                if (sequence < 1) {
                    throw new IllegalArgumentException("cursor is not valid");
                }
                return new Cursor(chain, sequence);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("cursor is not valid", e);
            }
        }
    }
}
