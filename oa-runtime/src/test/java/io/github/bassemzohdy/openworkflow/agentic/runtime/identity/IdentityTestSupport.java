package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Shared helpers for the identity tests: the database, a movable clock, a failing audit sink, small SQL helpers. */
final class IdentityTestSupport {

    static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic");
    static final String USER = System.getenv().getOrDefault("DB_USER", "postgres");
    static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "postgres");

    private static DataSource dataSource;

    private IdentityTestSupport() {
    }

    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            Flyway.configure().dataSource(URL, USER, PASSWORD).locations("classpath:db/migration").load().migrate();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(URL);
            ds.setUser(USER);
            ds.setPassword(PASSWORD);
            dataSource = ds;
        }
        return dataSource;
    }

    static String newTenant() {
        return "t-" + UUID.randomUUID();
    }

    static Subject user(String id) {
        return new Subject(SubjectKind.USER, id);
    }

    static Subject agent(String id) {
        return new Subject(SubjectKind.AGENT, id);
    }

    /** A clock that moves only when told to. Reads are microsecond-clean so database round trips compare equal. */
    static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now;

        MovableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** An audit sink that records events in memory, and can be told to fail. */
    static final class RecordingSink implements AuditSink {

        final List<AuditEvent> events = new ArrayList<>();
        volatile boolean failing;

        @Override
        public synchronized void record(AuditEvent event) {
            if (failing) {
                throw new IllegalStateException("audit trail down");
            }
            events.add(event);
        }

        synchronized List<AuditEvent> byAction(String action) {
            return events.stream().filter(e -> e.action().equals(action)).toList();
        }
    }

    static IdentityAudit audit(AuditSink sink, Clock clock) {
        return new IdentityAudit(sink, clock, UlidGenerator.shared());
    }

    static AuditTrail trail() {
        return new AuditTrail(dataSource());
    }

    static long count(String sql, Object... params) {
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs a statement and returns the error it raised, or null. */
    static SQLException tryExecute(String sql, Object... params) {
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }
}
