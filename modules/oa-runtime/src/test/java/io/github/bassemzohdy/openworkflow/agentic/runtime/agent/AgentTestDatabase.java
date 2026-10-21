package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The PostgreSQL database for graph and checkpoint tests. It reads {@code DB_URL} like the application and, before it
 * hands out any data source, migrates the database with the module's Flyway scripts. The graph saver creates its own
 * tables, so a test that builds a graph on a fresh database first would leave a non-empty schema without Flyway
 * history and break every later migrating test in the same build; migrating first makes the order irrelevant.
 * Never baseline, never clear shared tables.
 */
final class AgentTestDatabase {

    static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic");
    static final String USER = System.getenv().getOrDefault("DB_USER", "postgres");
    static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "postgres");

    private static boolean migrated;

    private AgentTestDatabase() {
    }

    /** A data source on the migrated database. */
    static synchronized DataSource dataSource() {
        migrate();
        return plain(null);
    }

    /** A data source whose search path is only {@code schema}, for tests of a missing or partial checkpoint schema. */
    static synchronized DataSource inSchema(String schema) {
        migrate();
        return plain(schema);
    }

    private static void migrate() {
        if (!migrated) {
            Flyway.configure().dataSource(URL, USER, PASSWORD).locations("classpath:db/migration").load().migrate();
            migrated = true;
        }
    }

    private static PGSimpleDataSource plain(String schema) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);
        if (schema != null) {
            ds.setCurrentSchema(schema);
        }
        return ds;
    }
}
