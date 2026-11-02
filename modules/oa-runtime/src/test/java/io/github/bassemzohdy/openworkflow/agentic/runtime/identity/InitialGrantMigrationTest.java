package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Migration against an isolated database created at the preceding production version. */
class InitialGrantMigrationTest {
    private static PGSimpleDataSource data(String database) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(database);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        return ds;
    }
    private static void insert(PGSimpleDataSource ds, String instance, String grant) throws SQLException {
        try (var c = ds.getConnection(); var q = c.prepareStatement("insert into identity.instance_authority "
                + "(instance_id,tenant,subject_kind,subject_id,grant_id,state,state_since,created_at,updated_at) "
                + "values (?,'tenant','USER','alice',?,'AUTHORIZED',clock_timestamp(),clock_timestamp(),clock_timestamp())")) {
            q.setString(1, instance); q.setString(2, grant); q.executeUpdate();
        }
    }
    @Test void upgradeLeavesLegacyUnknownAndCapturesAnImmutableOriginalForNewRows() throws Exception {
        String database = "owa_initial_grant_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(database.matches("owa_initial_grant_[0-9a-f]{32}"));
        boolean created = false;
        try {
            try (var c = data("postgres").getConnection(); var s = c.createStatement()) {
                s.execute("create database " + database); created = true;
            }
            var ds = data(database);
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("77").load().migrate();
            String legacy = UlidGenerator.shared().next().value(); String oldGrant = UlidGenerator.shared().next().value();
            insert(ds, legacy, oldGrant);
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("77.1").load().migrate();
            var store = new InstanceAuthorityStore(ds);
            var alice = new io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject(
                    io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind.USER, "alice");
            assertFalse(store.matchesInitialGrant(io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(legacy),
                    "tenant", alice, io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(oldGrant)));
            String fresh = UlidGenerator.shared().next().value(); String replacement = UlidGenerator.shared().next().value();
            insert(ds, fresh, oldGrant);
            var id = io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(fresh);
            var grant = io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(oldGrant);
            assertTrue(store.matchesInitialGrant(id, "tenant", alice, grant));
            assertFalse(store.matchesInitialGrant(id, "other-tenant", alice, grant));
            try (var c = ds.getConnection()) {
                try (var q = c.prepareStatement("update identity.instance_authority set grant_id=? where instance_id=?")) {
                    q.setString(1, replacement); q.setString(2, fresh); assertEquals(1, q.executeUpdate());
                }
                try (var q = c.prepareStatement("update identity.instance_authority set initial_grant_id=? where instance_id=?")) {
                    q.setString(1, replacement); q.setString(2, fresh); assertThrows(SQLException.class, q::executeUpdate);
                }
                try (var q = c.prepareStatement("update identity.instance_authority set initial_grant_known=true where instance_id=?")) {
                    q.setString(1, legacy); assertThrows(SQLException.class, q::executeUpdate);
                }
            }
            assertTrue(store.matchesInitialGrant(id, "tenant", alice, grant));
            assertFalse(store.matchesInitialGrant(id, "tenant", alice,
                    io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(replacement)));
            String none = UlidGenerator.shared().next().value(); insert(ds, none, null);
            assertTrue(store.matchesInitialGrant(io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(none), "tenant", alice, null));
            String forged = UlidGenerator.shared().next().value();
            try (var c = ds.getConnection(); var q = c.prepareStatement("insert into identity.instance_authority "
                    + "(instance_id,tenant,subject_kind,subject_id,grant_id,state,state_since,created_at,updated_at,initial_grant_known,initial_grant_id) "
                    + "values (?,'tenant','USER','alice',?,'AUTHORIZED',clock_timestamp(),clock_timestamp(),clock_timestamp(),false,?)")) {
                q.setString(1, forged); q.setString(2, oldGrant); q.setString(3, replacement); q.executeUpdate();
            }
            assertTrue(store.matchesInitialGrant(io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(forged), "tenant", alice, grant));
            assertFalse(store.matchesInitialGrant(io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(forged), "tenant", alice,
                    io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid.parse(replacement)));
        } finally {
            if (created) try (var c = data("postgres").getConnection(); var s = c.createStatement()) {
                s.execute("drop database " + database);
            }
        }
    }
}
