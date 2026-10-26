package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.CheckpointProfileVerifier.Reason;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The agent consumes the checkpoint profile that migration V75.1 owns and never creates or repairs it. Each test owns a
 * private database (see {@link AgentTestDatabase.Private}); nothing here touches the shared test database.
 */
class ResearchAgentCheckpointProfileTest {

    private static final String OK = "{\"text\":\"1. a 2. b 3. c\",\"actions\":[],\"escalation\":null}";

    private static ResearchAgent agent(DataSource dataSource, AtomicInteger providerCalls) throws Exception {
        return agent(dataSource, providerCalls, "public");
    }

    private static ResearchAgent agent(DataSource dataSource, AtomicInteger providerCalls, String configuredSchema)
            throws Exception {
        ResearchAgent agent = new ResearchAgent();
        agent.checkpointSchema = configuredSchema; // manual fixture: what the configuration injects in production
        agent.llm = GovernedFixtures.allowing((s, u) -> {
            providerCalls.incrementAndGet();
            return OK;
        });
        agent.dataSource = dataSource;
        agent.outputValidator = new AgentOutputValidator(new ObjectMapper(), target -> false);
        agent.build();
        return agent;
    }

    private static ResearchAgent unbuilt(DataSource dataSource) {
        ResearchAgent agent = new ResearchAgent();
        agent.checkpointSchema = "public";
        agent.dataSource = dataSource;
        return agent;
    }

    private static long count(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void buildOnAnUnmigratedDatabaseIsRefusedAndCreatesNoTables() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(false)) {
            AtomicInteger calls = new AtomicInteger();
            var refused = assertThrows(CheckpointProfileException.class, () -> agent(db.dataSource(), calls));
            assertSame(Reason.MISSING, refused.reason());
            assertEquals(0, count(db.dataSource(), "select count(*) from pg_class where relname like 'lg4j%'"),
                    "the saver must not create its tables (createTables(true) is gone)");
            assertEquals(0, calls.get());
        }
    }

    @Test
    void buildOnAPartialLegacyStoreIsRefusedAndNeitherCompletedNorChanged() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(false)) {
            execute(db.dataSource(), "create table public.lg4jthread(thread_id uuid primary key)");
            assertSame(Reason.MISSING, assertThrows(CheckpointProfileException.class,
                    () -> agent(db.dataSource(), new AtomicInteger())).reason());
            assertEquals(1, count(db.dataSource(), "select count(*) from pg_class where relname like 'lg4j%' and relkind='r'"));
        }
    }

    @Test
    void aMigratedStoreRunsTheGraphAndThePositivePathIsUnchanged() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            AtomicInteger calls = new AtomicInteger();
            ResearchAgent agent = agent(db.dataSource(), calls);
            String thread = "profile-ok-" + UUID.randomUUID();
            agent.run(thread, "topic");
            assertEquals(3, calls.get());
            assertSame(ResearchRunInspector.Kind.COMPLETE, new ResearchRunInspector(db.dataSource()).inspect(thread).kind());
            agent.onStart(null);
        }
    }

    @Test
    void driftAfterTheGraphWasBuiltIsRefusedBeforeAnyProviderCallAndWritesNothing() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            AtomicInteger calls = new AtomicInteger();
            ResearchAgent agent = agent(db.dataSource(), calls);
            agent.run("before-drift-" + UUID.randomUUID(), "topic");
            int before = calls.get();
            long rows = count(db.dataSource(), "select count(*) from public.lg4jthread");
            execute(db.dataSource(), "drop index public.idx_lg4jcheckpoint_thread_id");

            var refused = assertThrows(CheckpointProfileException.class,
                    () -> agent.run("after-drift-" + UUID.randomUUID(), "topic"));
            assertSame(Reason.INDEXES, refused.reason());
            assertFalse(refused.getMessage().contains("idx_"), "only the fixed code is shown");
            assertEquals(before, calls.get(), "no provider call after the store drifted");
            assertEquals(rows, count(db.dataSource(), "select count(*) from public.lg4jthread"), "no run was entered");
        }
    }

    @Test
    void theStartupCheckRefusesADriftedStoreAndAcceptsTheMigratedOne() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            ResearchAgent agent = unbuilt(db.dataSource());
            agent.onStart(null);
            execute(db.dataSource(), "alter table public.lg4jthread enable row level security");
            assertSame(Reason.RELATION, assertThrows(CheckpointProfileException.class, () -> agent.onStart(null)).reason());
        }
    }

    @Test
    void theInspectorRefusesADriftedStoreInsteadOfReportingNoCheckpoints() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            execute(db.dataSource(), "alter table public.lg4jthread add column unknown_extension text");
            var refused = assertThrows(CheckpointProfileException.class,
                    () -> new ResearchRunInspector(db.dataSource()).inspect("anything"));
            assertSame(Reason.COLUMNS, refused.reason());
        }
    }

    @Test
    void aConfiguredNamespaceOtherThanPublicIsRefusedAtStartBuildAndRunEvenWithAValidPublicProfile() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            for (String configured : new String[] {"unsupported_checkpoint_target", "PUBLIC", " public", "", "public.x", null}) {
                AtomicInteger calls = new AtomicInteger();
                assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class,
                        () -> agent(db.dataSource(), calls, configured)).reason(), "build: " + configured);
                ResearchAgent unbuilt = unbuilt(db.dataSource());
                unbuilt.checkpointSchema = configured;
                assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class,
                        () -> unbuilt.onStart(null)).reason(), "start: " + configured);
                assertEquals(0, calls.get());
            }
            // a graph built for public is refused too once its configured namespace is anything else
            AtomicInteger calls = new AtomicInteger();
            ResearchAgent agent = agent(db.dataSource(), calls);
            agent.checkpointSchema = "unsupported_checkpoint_target";
            assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class,
                    () -> agent.run("misconfigured-" + UUID.randomUUID(), "topic")).reason());
            assertEquals(0, calls.get(), "no provider call");
            assertEquals(0, count(db.dataSource(), "select count(*) from public.lg4jthread"), "no run was entered");
        }
    }

    @Test
    void aConfiguredNamespaceIsRefusedBeforeAnyConnectionIsTaken() {
        DataSource unusable = new PGSimpleDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("the store must not be touched for an unsupported namespace");
            }
        };
        ResearchAgent agent = unbuilt(unusable);
        agent.checkpointSchema = "unsupported_checkpoint_target";
        assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class, () -> agent.onStart(null)).reason());
        assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class, agent::build).reason());
        assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class, () -> agent.run("t", "x")).reason());
    }

    /**
     * Negative control for catalog-function shadowing. The owned schema {@code shadow} holds wrong checkpoint tables
     * and its own {@code current_schema()} and {@code to_regclass(text)}: the first claims the current schema is
     * public, the second forwards only to {@code pg_catalog.to_regclass} of the public-qualified names. With the search
     * path {@code shadow, public, pg_catalog}, unqualified catalog calls are answered by them (the previous check
     * accepted this store) while the saver's unqualified table names resolve to the shadow tables.
     */
    @Test
    void shadowedCatalogFunctionsCannotMakeAShadowSchemaPassAsPublic() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            execute(db.dataSource(), "create schema shadow");
            execute(db.dataSource(), "create table shadow.lg4jthread(thread_id uuid primary key)");
            execute(db.dataSource(), "create table shadow.lg4jcheckpoint(checkpoint_id uuid primary key)");
            execute(db.dataSource(), "create function shadow.current_schema() returns name"
                    + " language sql as 'select ''public''::name'");
            execute(db.dataSource(), "create function shadow.to_regclass(text) returns regclass language sql as"
                    + " 'select pg_catalog.to_regclass(case $1 when ''lg4jthread'' then ''public.lg4jthread''"
                    + " when ''lg4jcheckpoint'' then ''public.lg4jcheckpoint'' else $1 end)'");
            PGSimpleDataSource shadowFirst = db.dataSource();
            shadowFirst.setOptions("-c search_path=shadow,public,pg_catalog");
            // control: the shadows really lie to unqualified calls on this connection; the catalog does not
            try (Connection c = shadowFirst.getConnection(); Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("select current_schema()::text, pg_catalog.current_schema()::text,"
                            + " to_regclass('lg4jthread')::oid = 'public.lg4jthread'::regclass::oid,"
                            + " pg_catalog.to_regclass('lg4jthread')::oid = 'shadow.lg4jthread'::regclass::oid")) {
                rs.next();
                assertEquals("public", rs.getString(1));
                assertEquals("shadow", rs.getString(2));
                assertTrue(rs.getBoolean(3));
                assertTrue(rs.getBoolean(4));
            }

            AtomicInteger calls = new AtomicInteger();
            var refused = assertThrows(CheckpointProfileException.class, () -> agent(shadowFirst, calls));
            assertTrue(refused.reason() == Reason.NAMESPACE || refused.reason() == Reason.SHADOWED,
                    "refused as namespace/shadow, was " + refused.reason());
            assertEquals(0, calls.get());
            assertSame(refused.reason(), assertThrows(CheckpointProfileException.class,
                    () -> unbuilt(shadowFirst).onStart(null)).reason());
        }
    }

    @Test
    void aSearchPathWithoutPublicIsRefusedEvenWhenPublicHoldsTheProfile() throws Exception {
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            execute(db.dataSource(), "create schema elsewhere");
            PGSimpleDataSource other = db.dataSource("elsewhere");
            assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class,
                    () -> agent(other, new AtomicInteger())).reason());
            assertSame(Reason.NAMESPACE, assertThrows(CheckpointProfileException.class,
                    () -> new ResearchRunInspector(other).inspect("anything")).reason());
        }
    }
}
