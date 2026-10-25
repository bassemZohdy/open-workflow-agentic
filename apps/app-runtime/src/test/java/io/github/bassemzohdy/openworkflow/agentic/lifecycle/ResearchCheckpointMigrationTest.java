package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchOutputFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Ordered migration and actual saver permissions in private databases, without graph/provider or CDI adoption. */
class ResearchCheckpointMigrationTest {
    @Test
    void freshFlywayMigrationOwnsTheProfileBeforeTheSaverWrites() throws Exception {
        try (Database db = new Database()) {
            db.migrate(null);
            db.flyway(null).validate();
            assertEquals(1, scalar(db.owner, "select count(*) from flyway_schema_history where version='75.1' and success"));
            assertEquals(0, scalar(db.owner, "select count(*) from public.lg4jthread"));
            var config = config();
            var checkpoint = checkpoint("fresh");
            saver(db.owner, false).put(config, checkpoint);
            assertEquals(checkpoint.id(), saver(db.owner, false).get(config).orElseThrow().id());
            assertEquals(1, scalar(db.owner, "select count(*) from public.lg4jcheckpoint"));
        }
    }

    @Test
    void nonemptyVendorProfileKeepsPhysicalHistoryAndSupportsRealUpdatesAndRelease() throws Exception {
        try (Database db = new Database()) {
            db.migrate("75");
            var config = config();
            var original = checkpoint("before-adoption");
            // Simulates the existing SDK-owned profile only in this disposable legacy fixture.
            var stored = saver(db.owner, true).put(config, original);
            var before = snapshot(db.owner);
            db.migrate(null);
            db.flyway(null).validate();
            assertEquals(before, snapshot(db.owner));
            assertEquals(original.id(), saver(db.owner, false).get(config).orElseThrow().id());
            var replacement = checkpoint("after-adoption");
            saver(db.owner, false).put(stored, replacement);
            var observed = saver(db.owner, false).get(config).orElseThrow();
            assertEquals(replacement.id(), observed.id());
            assertEquals(replacement.state(), observed.state());
            saver(db.owner, false).release(config);
            assertEquals(1, scalar(db.owner, "select count(*) from public.lg4jthread where is_released"));
            assertEquals(1, scalar(db.owner, "select count(*) from public.lg4jcheckpoint"));
        }
    }

    @Test
    void partialLegacyProfileRefusesMigrationWithoutCompletingOrResettingIt() throws Exception {
        try (Database db = new Database()) {
            db.migrate("75");
            execute(db.owner, "create table public.lg4jthread(thread_id uuid primary key); "
                    + "insert into public.lg4jthread values('19b2ca22-659a-4904-b297-c8ddab7b1de7')");
            assertThrows(FlywayException.class, () -> db.migrate(null));
            assertEquals(1, scalar(db.owner, "select count(*) from public.lg4jthread"));
            assertEquals(0, scalar(db.owner, "select count(*) from pg_class where oid=to_regclass('public.lg4jcheckpoint')"));
            assertEquals(1, scalar(db.owner, "select count(*) from information_schema.columns where table_schema='public' and table_name='lg4jthread'"));
            assertEquals(0, scalar(db.owner, "select count(*) from flyway_schema_history where version='75.1' and success"));
        }
    }

    @Test
    void exactGrantTemplateSupportsMutableSaverButRefusesReaderWritesDdlAndOtherStores() throws Exception {
        try (Database db = new Database()) {
            db.migrate(null);
            String writer = db.role("writer"), reader = db.role("reader");
            execute(db.owner, Files.readString(repositoryRoot().resolve("deploy/sql/research-checkpoint-grants.sql"))
                    .replace("__CHECKPOINT_WRITER_ROLE__", writer).replace("__CHECKPOINT_READER_ROLE__", reader));
            DataSource write = db.as(writer), read = db.as(reader);
            var config = config();
            var first = checkpoint("first");
            var stored = saver(write, false).put(config, first);
            var second = checkpoint("second");
            saver(write, false).put(stored, second);
            assertEquals(second.state(), saver(write, false).get(config).orElseThrow().state());
            try (var c = read.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
                    "select state_content_type,state_data from public.lg4jcheckpoint where octet_length(state_data)<=4194304")) {
                assertTrue(r.next());
                assertTrue(ResearchOutputFingerprint.of(r.getString(1), r.getString(2)).matches("[0-9a-f]{64}"));
                assertFalse(r.next());
            }
            saver(write, false).release(config);
            assertEquals(1, scalar(read, "select count(*) from public.lg4jthread where is_released"));
            for (DataSource role : List.of(write, read)) {
                for (String sql : List.of("delete from public.lg4jcheckpoint", "delete from public.lg4jthread",
                        "truncate public.lg4jcheckpoint", "create table public.forbidden_checkpoint_test(id int)",
                        "alter table public.lg4jcheckpoint add column forbidden_test int",
                        "select * from effect.ledger", "update effect.ledger set detail=detail", "select * from audit.event"))
                    denied(role, sql);
            }
            denied(read, "update public.lg4jcheckpoint set state_data=state_data");
            denied(read, "insert into public.lg4jthread values(gen_random_uuid(),'forbidden',false)");
            assertEquals(1, scalar(db.owner, "select count(*) from public.lg4jcheckpoint"));
        }
    }

    private static RunnableConfig config() { return RunnableConfig.builder().threadId("owned-" + UUID.randomUUID()).build(); }
    private static Checkpoint checkpoint(String value) {
        return Checkpoint.builder().state(Map.of(ResearchState.TOPIC, "owned", ResearchState.PLAN, value))
                .nodeId("plan").nextNodeId("draft").build();
    }
    private static PostgresSaver saver(DataSource data, boolean create) throws Exception {
        return PostgresSaver.builder().datasource(data).createTables(create)
                .stateSerializer(new ObjectStreamStateSerializer<>(ResearchState::new)).build();
    }
    private static List<String> snapshot(DataSource data) throws SQLException {
        var rows = new ArrayList<String>();
        try (var c = data.getConnection(); var s = c.createStatement()) {
            for (String table : List.of("lg4jthread", "lg4jcheckpoint"))
                try (var r = s.executeQuery("select row_to_json(t)::text from public." + table + " t order by 1")) {
                    while (r.next()) rows.add(table + ":" + r.getString(1));
                }
        }
        return rows;
    }
    private static void denied(DataSource data, String sql) {
        var error = assertThrows(SQLException.class, () -> execute(data, sql), sql);
        assertEquals("42501", error.getSQLState(), sql);
        assertTrue(error.getMessage().contains("permission denied") || error.getMessage().contains("must be owner"), error.getMessage());
    }
    private static long scalar(DataSource data, String sql) throws SQLException {
        try (var c = data.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
    private static void execute(DataSource data, String sql) throws SQLException {
        try (var c = data.getConnection(); var s = c.createStatement()) { s.setQueryTimeout(20); s.execute(sql); }
    }
    private static Path repositoryRoot() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isRegularFile(p.resolve("deploy/sql/research-checkpoint-grants.sql"))) return p;
        throw new IllegalStateException("checkpoint grant template is missing");
    }

    private static final class Database implements AutoCloseable {
        private static final String PASSWORD = "checkpoint-disposable-test-only";
        private final String name = "oa_ck_migration_" + UUID.randomUUID().toString().replace("-", "");
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
            String role = "oa_ck_" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
            execute(admin(), "create role " + role + " login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '" + PASSWORD + "'");
            roles.add(role); return role;
        }
        DataSource as(String role) { return data(name, role, PASSWORD); }
        @Override public void close() throws SQLException {
            assertTrue(name.matches("oa_ck_migration_[0-9a-f]{32}"));
            execute(admin(), "drop database " + name);
            for (String role : roles) execute(admin(), "drop role " + role);
        }
        private static DataSource admin() {
            return data("postgres", System.getenv().getOrDefault("DB_USER", "postgres"), System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        }
        private static DataSource data(String database, String user, String password) {
            var ds = new PGSimpleDataSource();
            ds.setURL(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
            ds.setDatabaseName(database); ds.setUser(user); ds.setPassword(password); return ds;
        }
    }
}
