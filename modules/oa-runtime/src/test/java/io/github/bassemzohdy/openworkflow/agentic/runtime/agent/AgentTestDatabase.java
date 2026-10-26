package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The PostgreSQL database for graph and checkpoint tests. It reads {@code DB_URL} like the application and, before it
 * hands out any data source, migrates the database with the module's Flyway scripts. Migration V75.1 owns the
 * checkpoint tables and the agent never creates them, so a graph can only be built on a migrated database.
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

    /**
     * A throwaway database owned by one test, optionally migrated with the module's Flyway scripts, for tests that
     * damage the checkpoint profile. It is dropped on close; the shared database is never touched.
     */
    static Private privateDatabase(boolean migrated) throws SQLException {
        Private database = new Private();
        if (migrated) {
            Flyway.configure().dataSource(database.dataSource()).locations("classpath:db/migration").load().migrate();
        }
        return database;
    }

    static final class Private implements AutoCloseable {
        private final String name = "oa_agent_profile_" + UUID.randomUUID().toString().replace("-", "");

        private Private() throws SQLException {
            execute("create database " + name);
        }

        PGSimpleDataSource dataSource() {
            return dataSource(null);
        }

        PGSimpleDataSource dataSource(String schema) {
            PGSimpleDataSource ds = plain(schema);
            ds.setDatabaseName(name);
            return ds;
        }

        @Override
        public void close() throws SQLException {
            execute("drop database " + name + " with (force)");
        }

        private static void execute(String sql) throws SQLException {
            PGSimpleDataSource admin = plain(null);
            admin.setDatabaseName("postgres");
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }
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
