package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditDraftFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditReceipt;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditSource;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditQuery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSourceConflictException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditStoreException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Actual ordered V75 and its deployment grants; no CDI, caller authority or acceptance closure is claimed. */
class AuditSourceBindingMigrationTest {
    private static final Subject ACTOR = new Subject(SubjectKind.SERVICE, "v75-migration-test");
    private static final Instant AT = Instant.parse("2026-10-26T00:00:00.123456Z");

    @Test
    void freshMigrationSupportsConcurrentExactRetriesAndRejectsChangedSource() throws Exception {
        try (Database db = new Database()) {
            db.migrate(null);
            db.flyway(null).validate();
            assertEquals(1, scalar(db.owner, "select count(*) from flyway_schema_history where version='75' and success"));
            AuditEvent draft = draft("fresh", "original");
            AuditSource source = source(draft, 1);
            AuditAppendOnce once = new AuditAppendOnce(db.owner);
            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(6);
            List<Future<AuditReceipt>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> { start.await(); return once.appendOnce(source, draft); }));
                start.countDown();
                AuditReceipt receipt = futures.get(0).get(30, TimeUnit.SECONDS);
                for (var future : futures) assertEquals(receipt, future.get(30, TimeUnit.SECONDS));
                receipt.requireMatches(source, draft);
                assertEquals(receipt, once.appendOnce(source, draft));
                assertEquals(receipt, new AuditAppendOnce(db.owner).appendOnce(source, draft));
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            }
            AuditEvent changed = new AuditEvent(draft.id(), 1, AT, ACTOR, draft.action(), draft.resourceType(),
                    "changed", draft.attributes(), AuditHash.GENESIS, AuditHash.GENESIS);
            assertThrows(AuditSourceConflictException.class, () -> once.appendOnce(source(changed, 1), changed));
            assertCounts(db.owner, 1);
        }
    }

    @Test
    void upgradingNonemptyV74PreservesEventsWithoutInventingSourceBackfill() throws Exception {
        try (Database db = new Database()) {
            db.migrate("74");
            AuditEvent old = new AuditTrail(db.owner).append(draft("upgrade", "before-v75"));
            db.migrate(null);
            db.flyway(null).validate();
            assertEquals(old, new AuditQuery(db.owner).byId(old.id()).orElseThrow());
            assertEquals(0, scalar(db.owner, "select count(*) from audit.source_binding"));
            assertEquals(1, scalar(db.owner, "select count(*) from audit.event"));
            AuditEvent next = draft("upgrade", "after-v75");
            AuditReceipt receipt = new AuditAppendOnce(db.owner).appendOnce(source(next, 1), next);
            assertEquals(2, receipt.chainSequence());
            assertEquals(old, new AuditQuery(db.owner).byId(old.id()).orElseThrow());
            assertEquals(2, scalar(db.owner, "select count(*) from audit.head_log"));
            assertEquals(1, scalar(db.owner, "select count(*) from audit.source_binding"));
        }
    }

    @Test
    void anUnexpectedPreexistingSourceTableFailsMigrationWithoutChangingRetainedData() throws Exception {
        try (Database db = new Database()) {
            db.migrate("74");
            AuditEvent old = new AuditTrail(db.owner).append(draft("refused-upgrade", "retained"));
            execute(db.owner, "create table audit.source_binding(sentinel text not null); insert into audit.source_binding values('keep')");
            assertThrows(FlywayException.class, () -> db.migrate(null));
            assertEquals(old, new AuditQuery(db.owner).byId(old.id()).orElseThrow());
            assertEquals(1, scalar(db.owner, "select count(*) from audit.source_binding where sentinel='keep'"));
            assertEquals(0, scalar(db.owner, "select count(*) from flyway_schema_history where version='75' and success"));
            assertEquals(0, scalar(db.owner, "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace "
                    + "where n.nspname='audit' and p.proname='source_identity_key'"));
        }
    }

    @Test
    void appliedSchemaRefusesForgedIdentityAndReceiptsAndRetainsOneEventPerSource() throws Exception {
        try (Database db = new Database()) {
            db.migrate(null);
            AuditEvent draft = draft("references", "original");
            AuditReceipt receipt = new AuditAppendOnce(db.owner).appendOnce(source(draft, 1), draft);
            AuditSource other = source(draft, 2);
            assertState("23514", () -> bind(db.owner, other, source(draft, 1).key(), receipt.eventId().value(),
                    receipt.chainId(), receipt.chainSequence(), receipt.hash()));
            assertState("23503", () -> bind(db.owner, other, other.key(), UlidGenerator.shared().next().value(),
                    receipt.chainId(), receipt.chainSequence(), receipt.hash()));
            assertState("23514", () -> bind(db.owner, other, other.key(), receipt.eventId().value(),
                    "other-chain", receipt.chainSequence(), receipt.hash()));
            assertState("23514", () -> bind(db.owner, other, other.key(), receipt.eventId().value(),
                    receipt.chainId(), receipt.chainSequence() + 1, receipt.hash()));
            assertState("23514", () -> bind(db.owner, other, other.key(), receipt.eventId().value(),
                    receipt.chainId(), receipt.chainSequence(), "f".repeat(64)));
            assertState("23505", () -> bind(db.owner, other, other.key(), receipt.eventId().value(),
                    receipt.chainId(), receipt.chainSequence(), receipt.hash()));
            assertCounts(db.owner, 1);
        }
    }

    @Test
    void appliedSourceGuardsRejectOwnerMutationAndAlwaysRollbackTruncate() throws Exception {
        try (Database db = new Database()) {
            db.migrate(null);
            AuditEvent draft = draft("immutable", "original");
            new AuditAppendOnce(db.owner).appendOnce(source(draft, 1), draft);
            for (String sql : List.of("update audit.source_binding set hash=hash", "delete from audit.source_binding")) {
                SQLException error = assertThrows(SQLException.class, () -> execute(db.owner, sql));
                assertEquals("42501", error.getSQLState());
                assertTrue(error.getMessage().contains("audit table source_binding is append-only"));
            }
            try (Connection c = db.owner.getConnection()) {
                c.setAutoCommit(false);
                try {
                    SQLException error = assertThrows(SQLException.class, () -> { try (var s = c.createStatement()) { s.execute("truncate audit.source_binding"); } });
                    assertEquals("42501", error.getSQLState());
                    assertTrue(error.getMessage().contains("audit table source_binding is append-only"));
                } finally { c.rollback(); }
            }
            assertCounts(db.owner, 1);
        }
    }

    @Test
    void exactDeploymentGrantsAllowAppendAndReadButRefuseMutationDdlAndLedgerAccess() throws Exception {
        try (Database db = new Database()) {
            db.migrate(null);
            String writer = db.role("writer"), reader = db.role("reader");
            String grants = Files.readString(repositoryRoot().resolve("deploy/sql/audit-source-grants.sql"))
                    .replace("__AUDIT_WRITER_ROLE__", writer).replace("__AUDIT_READER_ROLE__", reader);
            execute(db.owner, grants);
            DataSource write = db.as(writer), read = db.as(reader);
            AuditEvent draft = draft("roles", "original");
            AuditSource source = source(draft, 1);
            AuditReceipt receipt = new AuditAppendOnce(write).appendOnce(source, draft);
            assertEquals(receipt, new AuditAppendOnce(write).appendOnce(source, draft));
            assertEquals(receipt.hash(), new AuditQuery(read).byId(draft.id()).orElseThrow().hash());
            assertCounts(read, 1);
            for (DataSource role : List.of(write, read)) {
                for (String sql : List.of("update audit.source_binding set hash=hash", "delete from audit.source_binding",
                        "truncate audit.source_binding", "update audit.event set hash=hash", "delete from audit.head_log",
                        "create table audit.forbidden_test(id int)", "alter table audit.source_binding add column forbidden_test int",
                        "select * from effect.ledger", "delete from effect.ledger")) assertPermissionDenied(role, sql);
            }
            assertPermissionDenied(read, "insert into audit.source_binding select * from audit.source_binding");
            AuditEvent refused = draft("roles", "reader-cannot-append");
            assertThrows(AuditStoreException.class, () -> new AuditAppendOnce(read).appendOnce(source(refused, 2), refused));
            assertCounts(db.owner, 1);
        }
    }

    private static AuditEvent draft(String chain, String resource) {
        return new AuditEvent(UlidGenerator.shared().next(), 1, AT, ACTOR, "effect.intent", "effect", resource,
                Map.of("oa.chain", chain), AuditHash.GENESIS, AuditHash.GENESIS);
    }

    private static AuditSource source(AuditEvent draft, long sequence) {
        return new AuditSource(1, "a".repeat(64), "b".repeat(64), AuditSource.Transition.INTENT,
                sequence, AuditDraftFingerprint.of(draft));
    }

    private static void bind(DataSource data, AuditSource source, String key, String event, String chain, long sequence, String hash)
            throws SQLException {
        try (var c = data.getConnection(); var s = c.prepareStatement("""
                insert into audit.source_binding(source_key,schema_version,scope_fingerprint,key_fingerprint,
                    transition,source_sequence,content_fingerprint,event_id,chain_id,chain_sequence,hash)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            s.setString(1, key); s.setInt(2, source.schemaVersion()); s.setString(3, source.scopeFingerprint());
            s.setString(4, source.keyFingerprint()); s.setString(5, source.transition().name()); s.setLong(6, source.sequence());
            s.setString(7, source.contentFingerprint()); s.setString(8, event); s.setString(9, chain);
            s.setLong(10, sequence); s.setString(11, hash); s.executeUpdate();
        }
    }

    private static void assertCounts(DataSource data, long count) throws SQLException {
        for (String table : List.of("audit.event", "audit.head_log", "audit.source_binding"))
            assertEquals(count, scalar(data, "select count(*) from " + table), table);
    }

    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }

    private static void assertState(String state, SqlAction action) {
        assertEquals(state, assertThrows(SQLException.class, action::run).getSQLState());
    }

    private static void assertPermissionDenied(DataSource data, String sql) {
        SQLException error = assertThrows(SQLException.class, () -> execute(data, sql), sql);
        assertEquals("42501", error.getSQLState(), sql);
        assertTrue(error.getMessage().contains("permission denied") || error.getMessage().contains("must be owner"),
                "Expected a privilege refusal, not an append-only trigger: " + error.getMessage());
    }

    private static long scalar(DataSource data, String sql) throws SQLException {
        try (var c = data.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    private static void execute(DataSource data, String sql) throws SQLException {
        try (var c = data.getConnection(); var s = c.createStatement()) { s.execute(sql); }
    }

    private static Path repositoryRoot() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isRegularFile(p.resolve("deploy/sql/audit-source-grants.sql"))) return p;
        throw new IllegalStateException("audit grant template is missing from the repository");
    }

    private static final class Database implements AutoCloseable {
        private static final String PASSWORD = "v75-disposable-test-only";
        private final String name = "oa_v75_" + UUID.randomUUID().toString().replace("-", "");
        private final DataSource owner;
        private final List<String> roles = new ArrayList<>();

        Database() throws SQLException {
            execute(admin(), "create database " + name);
            owner = data(name, System.getenv().getOrDefault("DB_USER", "postgres"), System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        }

        Flyway flyway(String target) {
            var config = Flyway.configure().dataSource(owner).locations("classpath:db/migration");
            if (target != null) config.target(target);
            return config.load();
        }

        void migrate(String target) { flyway(target).migrate(); }

        String role(String kind) throws SQLException {
            String role = "oa_v75_" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
            execute(admin(), "create role " + role + " login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '" + PASSWORD + "'");
            roles.add(role);
            return role;
        }

        DataSource as(String role) { return data(name, role, PASSWORD); }

        @Override public void close() throws SQLException {
            execute(admin(), "drop database " + name + " with (force)");
            for (String role : roles) execute(admin(), "drop role " + role);
        }

        private static DataSource admin() {
            return data("postgres", System.getenv().getOrDefault("DB_USER", "postgres"), System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        }

        private static DataSource data(String database, String user, String password) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
            ds.setDatabaseName(database); ds.setUser(user); ds.setPassword(password);
            return ds;
        }
    }
}
