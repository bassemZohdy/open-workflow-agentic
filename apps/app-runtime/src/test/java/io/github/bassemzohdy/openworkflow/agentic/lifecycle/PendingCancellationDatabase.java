package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Disposable database for pending-cancellation transaction tests. */
public final class PendingCancellationDatabase implements QuarkusTestResourceLifecycleManager {
    private final String database = "owa_pcancel_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;
    private PGSimpleDataSource data(String name) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(name);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        return ds;
    }
    @Override public Map<String, String> start() {
        try {
            if (!database.matches("owa_pcancel_[0-9a-f]{32}")) throw new IllegalStateException("invalid database name");
            try (var c = data("postgres").getConnection(); var s = c.createStatement()) {
                s.execute("create database " + database); created = true;
            }
            var ds = data(database);
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            return Map.of("quarkus.datasource.jdbc.url", ds.getURL());
        } catch (Exception failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("isolated cancellation database could not start", failure);
        }
    }
    @Override public void stop() {
        if (!created) return;
        if (!database.matches("owa_pcancel_[0-9a-f]{32}")) throw new IllegalStateException("invalid database name");
        try (var c = data("postgres").getConnection(); var s = c.createStatement()) {
            s.execute("drop database " + database); created = false;
        } catch (Exception e) { throw new IllegalStateException("isolated cancellation database could not be removed", e); }
    }
}
