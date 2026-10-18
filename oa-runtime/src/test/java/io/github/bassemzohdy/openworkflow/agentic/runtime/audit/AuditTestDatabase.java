package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The PostgreSQL database for audit tests. It reads {@code DB_URL} (like the application), migrates it with the
 * module's Flyway scripts, and gives each test its own chain id so that tests do not see each other's events.
 */
final class AuditTestDatabase {

    static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic");
    static final String USER = System.getenv().getOrDefault("DB_USER", "postgres");
    static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "postgres");

    private static DataSource dataSource;

    private AuditTestDatabase() {
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

    /** A chain id no other test uses. */
    static String newChain() {
        return "t" + UlidGenerator.shared().next().value();
    }

    static Ulid newId() {
        return UlidGenerator.shared().next();
    }

    static Subject actor() {
        return new Subject(SubjectKind.SERVICE, "audit-test");
    }

    /** Attributes naming {@code chain}, plus any extra entries. */
    static Map<String, String> attributes(String chain, Map<String, String> extra) {
        var map = new java.util.LinkedHashMap<String, String>();
        map.put(AuditKeys.CHAIN, chain);
        map.putAll(extra);
        return map;
    }

    /** A raw connection, for tests that run plain SQL against the audit tables. */
    static Connection connection() throws SQLException {
        return dataSource().getConnection();
    }

    /** A connection as another role, for privilege tests. */
    static Connection connectionAs(String user, String password) throws SQLException {
        return DriverManager.getConnection(URL, user, password);
    }
}
