package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Own database with the human schema proposals; nothing is applied to the developer's existing database. */
public final class HumanSchemaResource implements QuarkusTestResourceLifecycleManager {
    private final String database = "owa_hcheckpoint_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;
    private static PGSimpleDataSource data(String database) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(database);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        return ds;
    }
    private static Path proposal(String name) {
        for (Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath(); root != null; root = root.getParent()) {
            var path = root.resolve("modules/oa-runtime/src/test/resources/human").resolve(name);
            if (Files.isRegularFile(path)) return path;
        }
        throw new IllegalStateException("human schema proposal not found");
    }
    @Override public Map<String, String> start() {
        try {
            String shared = System.getenv("HUMAN_CHECKPOINT_SHARED_URL");
            if (shared != null) {
                var ds = new PGSimpleDataSource();
                ds.setURL(shared);
                ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
                ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
                try (var c = ds.getConnection(); var s = c.createStatement(); var r = s.executeQuery("select current_database()")) {
                    if (!r.next() || !r.getString(1).matches("owa_hcheckpoint_[0-9a-f]{32}"))
                        throw new IllegalStateException("shared database is not an isolated human checkpoint test database");
                }
                return Map.of("quarkus.datasource.jdbc.url", ds.getURL());
            }
            if (!database.matches("owa_hcheckpoint_[0-9a-f]{32}")) throw new IllegalStateException("invalid test database name");
            try (var c = data("postgres").getConnection(); var s = c.createStatement()) { s.execute("create database " + database); created = true; }
            var ds = data(database);
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            for (String file : List.of("proposed-human-decision.sql", "proposed-registered-wait-fence.sql")) {
                try (var c = ds.getConnection(); var s = c.createStatement()) { s.execute(Files.readString(proposal(file))); }
            }
            return Map.of("quarkus.datasource.jdbc.url", ds.getURL());
        } catch (Exception failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("isolated human checkpoint database could not start", failure);
        }
    }
    @Override public void stop() {
        if (!created) return;
        if (!database.matches("owa_hcheckpoint_[0-9a-f]{32}")) throw new IllegalStateException("invalid test database name");
        try (var c = data("postgres").getConnection(); var s = c.createStatement()) { s.execute("drop database " + database); created = false; }
        catch (Exception e) { throw new IllegalStateException("isolated human checkpoint database could not be removed", e); }
    }
}
