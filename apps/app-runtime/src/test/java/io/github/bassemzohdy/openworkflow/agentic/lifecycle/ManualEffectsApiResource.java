package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/** Private DB and real signature verification against a fake issuer; only proposed V80 is explicitly rehearsed. */
public class ManualEffectsApiResource implements QuarkusTestResourceLifecycleManager {
    static FakeIdentityProvider idp;
    private String database;
    private final String server = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic");
    private final String user = System.getenv().getOrDefault("DB_USER", "postgres");
    private final String password = System.getenv().getOrDefault("DB_PASSWORD", "postgres");

    @Override
    public Map<String,String> start() {
        try {
            database = "manual_api_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = DriverManager.getConnection(server, user, password); var s = c.createStatement()) {
                s.execute("create database " + database);
            }
            String url = server.substring(0, server.lastIndexOf('/') + 1) + database;
            Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().migrate();
            try (var in = getClass().getClassLoader().getResourceAsStream("db/proposed/V80__manual_effect_decisions.sql");
                    var c = DriverManager.getConnection(url,user,password)) {
                if (in == null) throw new IllegalStateException("V80 proposal unavailable");
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                    s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8)); c.commit();
                } catch (Exception failure) { c.rollback(); throw failure; }
            }
            idp = new FakeIdentityProvider(Clock.systemUTC()).start();
            return Map.of("quarkus.datasource.jdbc.url",url,"quarkus.datasource.username",user,
                    "quarkus.datasource.password",password,"agentic.effects.manual-resolution.enabled","true",
                    "agentic.identity.issuer",idp.issuer(),"agentic.identity.jwks-uri",idp.jwksUri(),
                    "agentic.identity.audience","oa-api","agentic.dev.trust-caller-headers","false");
        } catch (Exception failure) { stop(); throw new IllegalStateException("manual API test setup failed", failure); }
    }

    @Override
    public void stop() {
        if (idp != null) { idp.close(); idp = null; }
        if (database != null && database.matches("manual_api_[0-9a-f]{32}")) {
            try (var c = DriverManager.getConnection(server,user,password); var s = c.createStatement()) {
                s.execute("drop database if exists " + database + " with (force)"); database = null;
            } catch (Exception failure) { throw new IllegalStateException("private manual API database cleanup failed", failure); }
        }
    }
}
