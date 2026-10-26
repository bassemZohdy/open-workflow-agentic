package io.github.bassemzohdy.openworkflow.agentic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.CheckpointProfileException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.CheckpointProfileVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.CheckpointProfileVerifier.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The research agent's runtime verifier against the actual public checkpoint profile, in private databases cloned from
 * one migrated template. The 15 synthetic malformed-profile mutations of the merged fixture
 * ({@code checkpoint-profile/mutations.json}) are applied only here; they are never runtime validation or repair.
 * Every mutation is applied AFTER a successful V75.1 migration, so Flyway history keeps saying "adopted" while the live
 * catalog has drifted: the verifier must trust the catalog, not the history. Plain JUnit and catalog SQL, no CDI.
 */
class CheckpointProfileConsumerTest {

    private static final String PASSWORD = "checkpoint-disposable-test-only";
    private static final String TEMPLATE = "oa_ck_consumer_tpl_" + UUID.randomUUID().toString().replace("-", "");
    private static final List<String> DATABASES = new ArrayList<>();
    private static final List<String> ROLES = new ArrayList<>();

    /** Expected refusal per mutation of the fixture; a new fixture entry must be classified here deliberately. */
    private static final Map<String, Reason> EXPECTED = Map.ofEntries(
            Map.entry("column-collation", Reason.COLUMNS), Map.entry("type", Reason.COLUMNS),
            Map.entry("nullability", Reason.COLUMNS), Map.entry("default", Reason.COLUMNS),
            Map.entry("primary-key", Reason.KEYS), Map.entry("foreign-key", Reason.KEYS),
            Map.entry("extra-column", Reason.COLUMNS), Map.entry("missing-index", Reason.INDEXES),
            Map.entry("predicate", Reason.INDEXES), Map.entry("sort-order", Reason.INDEXES),
            Map.entry("nulls-not-distinct", Reason.INDEXES), Map.entry("extra-unique", Reason.UNIQUENESS),
            Map.entry("row-security", Reason.RELATION), Map.entry("check", Reason.UNSUPPORTED_OBJECT),
            Map.entry("trigger", Reason.UNSUPPORTED_OBJECT));

    @BeforeAll
    static void migratedTemplate() throws Exception {
        execute(admin(), "create database " + TEMPLATE);
        Flyway.configure().dataSource(data(TEMPLATE, user(), password())).locations("classpath:db/migration")
                .load().migrate();
    }

    @AfterAll
    static void dropEverything() throws Exception {
        for (String db : DATABASES) {
            execute(admin(), "drop database if exists " + db + " with (force)");
        }
        execute(admin(), "drop database if exists " + TEMPLATE + " with (force)");
        for (String role : ROLES) {
            execute(admin(), "drop role if exists " + role);
        }
    }

    // ------------------------------------------------------------------ the 15 merged mutations

    @TestFactory
    List<DynamicTest> everyMergedMalformedProfileMutationIsRefusedAfterAMigrationThatSucceeded() throws Exception {
        Map<String, String> mutations = mutations();
        assertEquals(EXPECTED.keySet(), mutations.keySet(), "the fixture and its expected classification must agree");
        assertEquals(15, mutations.size());
        List<DynamicTest> tests = new ArrayList<>();
        mutations.forEach((id, sql) -> tests.add(dynamicTest(id, () -> {
            String db = freshDatabase();
            DataSource owner = data(db, user(), password());
            assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(owner), "control: the migrated profile is accepted");
            long adopted = scalar(owner, "select count(*) from flyway_schema_history where version='75.1' and success");
            assertEquals(1, adopted);
            execute(owner, sql.replace("%1$s", "public"));
            assertEquals(1, scalar(owner, "select count(*) from flyway_schema_history where version='75.1' and success"),
                    "the history still claims the adoption: drift is invisible to it");
            CheckpointProfileException refused = assertThrows(CheckpointProfileException.class,
                    () -> CheckpointProfileVerifier.verify(owner));
            assertSame(EXPECTED.get(id), refused.reason(), id);
            assertEquals("the checkpoint store does not match the pinned profile: " + refused.reason().code(),
                    refused.getMessage());
            // the inspector shares the verifier, so it refuses too instead of reading rows of a drifted store
            assertSame(EXPECTED.get(id), assertThrows(CheckpointProfileException.class,
                    () -> new ResearchRunInspector(owner).inspect("anything")).reason(), id);
        })));
        return tests;
    }

    // ------------------------------------------------------------------ special controls

    @Test
    void theMigratedProfileIsAcceptedWithoutConsultingFlywayHistory() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        execute(owner, "delete from flyway_schema_history where version='75.1'");
        assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(owner));
    }

    @Test
    void aSearchPathWhoseFirstSchemaIsNotPublicIsRefusedEvenIfPublicHoldsTheProfile() throws Exception {
        String db = freshDatabase();
        DataSource owner = data(db, user(), password());
        execute(owner, "create schema shadow; create table shadow.lg4jthread(thread_id uuid)");
        PGSimpleDataSource shadowed = data(db, user(), password());
        shadowed.setCurrentSchema("shadow,public");
        assertSame(Reason.NAMESPACE, refusal(shadowed));
        PGSimpleDataSource absent = data(db, user(), password());
        absent.setCurrentSchema("not_a_schema_" + UUID.randomUUID().toString().replace("-", ""));
        assertSame(Reason.NAMESPACE, refusal(absent));
    }

    @Test
    void aSessionTemporaryRelationThatShadowsTheSaversUnqualifiedNameIsRefused() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        try (Connection c = owner.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("create temp table lg4jthread(thread_id uuid)");
            }
            c.setAutoCommit(false);
            c.setReadOnly(true);
            CheckpointProfileException refused = assertThrows(CheckpointProfileException.class,
                    () -> CheckpointProfileVerifier.verify(c));
            assertSame(Reason.SHADOWED, refused.reason());
            c.rollback();
        }
        // control: the same data source on another connection has no such relation
        assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(owner));
    }

    @Test
    void aMissingPublicSchemaIsANamespaceRefusalAndAPartialStoreIsMissing() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        execute(owner, "drop table public.lg4jcheckpoint");
        assertSame(Reason.MISSING, refusal(owner));

        DataSource noSchema = data(freshDatabase(), user(), password());
        execute(noSchema, "drop schema public cascade");
        assertSame(Reason.NAMESPACE, refusal(noSchema));
    }

    @Test
    void inheritanceRulesAndNonPermanentTablesAreRefused() throws Exception {
        DataSource child = data(freshDatabase(), user(), password());
        execute(child, "create table public.lg4j_child() inherits (public.lg4jthread)");
        assertSame(Reason.UNSUPPORTED_OBJECT, refusal(child));

        DataSource parent = data(freshDatabase(), user(), password());
        execute(parent, "create table public.lg4j_parent(thread_id uuid); "
                + "alter table public.lg4jthread inherit public.lg4j_parent");
        assertSame(Reason.UNSUPPORTED_OBJECT, refusal(parent));

        DataSource rule = data(freshDatabase(), user(), password());
        execute(rule, "create rule ignore_delete as on delete to public.lg4jthread do instead nothing");
        assertSame(Reason.RELATION, refusal(rule));

        DataSource unlogged = data(freshDatabase(), user(), password());
        execute(unlogged, "alter table public.lg4jcheckpoint set unlogged; alter table public.lg4jthread set unlogged");
        assertSame(Reason.RELATION, refusal(unlogged));
    }

    @Test
    void storedRowsAreNeverDecodedSoGarbageStateDoesNotChangeTheProfileAnswer() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        execute(owner, "insert into public.lg4jthread values('2f6f0f40-0000-4000-8000-000000000001','t',false);"
                + "insert into public.lg4jcheckpoint(checkpoint_id,thread_id,node_id,next_node_id,state_data,state_content_type)"
                + " values('2f6f0f40-0000-4000-8000-000000000002','2f6f0f40-0000-4000-8000-000000000001',"
                + "'x','y','not-base64!','nonsense')");
        assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(owner));
    }

    @Test
    void theCheckRunsOnlyReadOnlyCatalogSqlAfterALockAsItsFirstStatementOnOneConnection() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        List<String> sql = new ArrayList<>();
        AtomicInteger connections = new AtomicInteger();
        DataSource recording = recording(owner, sql, connections);
        CheckpointProfileVerifier.verify(recording);
        assertEquals(1, connections.get());
        assertEquals("lock table public.lg4jthread, public.lg4jcheckpoint in access share mode nowait", sql.get(0));
        for (String statement : sql) {
            String lower = statement.toLowerCase();
            assertFalse(lower.contains("state_data") || lower.contains("flyway") || lower.matches(".*\\b(insert|update"
                    + "|delete|create|alter|drop|truncate|grant|revoke)\\b.*"), statement);
        }
        // the inspector verifies inside its one read transaction: one connection, the lock first
        sql.clear();
        connections.set(0);
        ResearchRunInspector.Inspection none = new ResearchRunInspector(recording).inspect("never-" + UUID.randomUUID());
        assertSame(ResearchRunInspector.Kind.NONE, none.kind());
        assertEquals(1, connections.get(), "no nested connection");
        assertTrue(sql.get(0).startsWith("lock table public.lg4jthread"), sql.get(0));
    }

    // ------------------------------------------------------------------ locks: only conflicting DDL is refused

    @Test
    void anOrdinaryWriterIsNotRefusedAndDoesNotBlockTheCheck() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        try (Connection writer = owner.getConnection()) {
            writer.setAutoCommit(false);
            try (Statement s = writer.createStatement()) {
                s.execute("insert into public.lg4jthread values(gen_random_uuid(),'writer',false)"); // ROW EXCLUSIVE
            }
            assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(owner));
            writer.rollback();
        }
    }

    @Test
    void theHeldShareLockLetsWritersCommitButRefusesDdl() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        try (Connection check = owner.getConnection()) {
            check.setAutoCommit(false);
            check.setReadOnly(true);
            CheckpointProfileVerifier.verify(check); // the lock is now held by this open transaction
            try (Connection other = owner.getConnection(); Statement s = other.createStatement()) {
                s.execute("set lock_timeout = '1500ms'");
                s.execute("insert into public.lg4jthread values(gen_random_uuid(),'writer-during-check',false)");
                SQLException ddl = assertThrows(SQLException.class,
                        () -> s.execute("alter table public.lg4jthread add column late_ddl text"));
                assertEquals("55P03", ddl.getSQLState());
            }
            check.rollback();
        }
    }

    @Test
    void conflictingExclusiveDdlIsRefusedImmediatelyNotWaitedFor() throws Exception {
        DataSource owner = data(freshDatabase(), user(), password());
        try (Connection ddl = owner.getConnection()) {
            ddl.setAutoCommit(false);
            try (Statement s = ddl.createStatement()) {
                s.execute("lock table public.lg4jthread in access exclusive mode");
            }
            long started = System.nanoTime();
            assertSame(Reason.LOCKED, refusal(owner));
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "NOWAIT: refused, never queued");
            assertSame(Reason.LOCKED, assertThrows(CheckpointProfileException.class,
                    () -> new ResearchRunInspector(owner).inspect("anything")).reason());
            ddl.rollback();
        }
        assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(owner));
    }

    // ------------------------------------------------------------------ reader and writer roles from the grant template

    @Test
    void theExactReaderAndWriterRolesPassTheCheckWithoutAnyOtherPrivilege() throws Exception {
        String db = freshDatabase();
        DataSource owner = data(db, user(), password());
        String writer = role("writer");
        String reader = role("reader");
        execute(owner, Files.readString(repositoryRoot().resolve("deploy/sql/research-checkpoint-grants.sql"))
                .replace("__CHECKPOINT_WRITER_ROLE__", writer).replace("__CHECKPOINT_READER_ROLE__", reader));
        DataSource asReader = data(db, reader, PASSWORD);
        DataSource asWriter = data(db, writer, PASSWORD);
        assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(asReader));
        assertDoesNotThrow(() -> CheckpointProfileVerifier.verify(asWriter));
        assertSame(ResearchRunInspector.Kind.NONE,
                new ResearchRunInspector(asReader).inspect("never-" + UUID.randomUUID()).kind());
        // a role with no access to the tables gets a fixed unavailable refusal, not a raw database message
        String nobody = role("nobody");
        CheckpointProfileException denied = assertThrows(CheckpointProfileException.class,
                () -> CheckpointProfileVerifier.verify(data(db, nobody, PASSWORD)));
        assertSame(Reason.UNAVAILABLE, denied.reason());
        assertFalse(denied.getMessage().contains("permission"), denied.getMessage());
    }

    // ------------------------------------------------------------------ helpers

    private static Reason refusal(DataSource ds) {
        return assertThrows(CheckpointProfileException.class, () -> CheckpointProfileVerifier.verify(ds)).reason();
    }

    private static Map<String, String> mutations() throws Exception {
        try (InputStream in = CheckpointProfileConsumerTest.class.getResourceAsStream("/checkpoint-profile/mutations.json")) {
            assertNotNull(in);
            JsonNode groups = new ObjectMapper().readTree(in).get("groups");
            Map<String, String> all = new LinkedHashMap<>();
            for (Iterator<JsonNode> group = groups.elements(); group.hasNext();) {
                group.next().fields().forEachRemaining(e -> all.put(e.getKey(), e.getValue().asText()));
            }
            return all;
        }
    }

    private static String freshDatabase() throws SQLException {
        String name = "oa_ck_consumer_" + UUID.randomUUID().toString().replace("-", "");
        DATABASES.add(name);
        execute(admin(), "create database " + name + " template " + TEMPLATE);
        return name;
    }

    private static String role(String kind) throws SQLException {
        String role = "oa_ck_consumer_" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
        execute(admin(), "create role " + role + " login noinherit nosuperuser nocreatedb nocreaterole noreplication"
                + " nobypassrls password '" + PASSWORD + "'");
        ROLES.add(role);
        return role;
    }

    /** Records every prepared statement and every connection taken from the data source. */
    private static DataSource recording(DataSource delegate, List<String> sql, AtomicInteger connections) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return call(method, delegate, args);
                    }
                    connections.incrementAndGet();
                    Connection real = delegate.getConnection();
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("prepareStatement")) {
                                    sql.add((String) ca[0]);
                                }
                                return call(cm, real, ca);
                            });
                });
    }

    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Path repositoryRoot() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("deploy/sql/research-checkpoint-grants.sql"))) {
                return p;
            }
        }
        throw new IllegalStateException("checkpoint grant template is missing");
    }

    private static String user() {
        return System.getenv().getOrDefault("DB_USER", "postgres");
    }

    private static String password() {
        return System.getenv().getOrDefault("DB_PASSWORD", "postgres");
    }

    private static PGSimpleDataSource data(String database, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(database);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static DataSource admin() {
        return data("postgres", user(), password());
    }

    private static long scalar(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.setQueryTimeout(180);
            s.execute(sql);
        }
    }
}
