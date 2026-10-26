package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        ResearchAgent agent = new ResearchAgent();
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
