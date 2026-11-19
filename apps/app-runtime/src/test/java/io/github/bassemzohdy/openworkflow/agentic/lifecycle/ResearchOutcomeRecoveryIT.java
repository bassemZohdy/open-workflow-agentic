package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Actual graph/checkpoints across JVM halts; identity, approval, policy and provider remain fixtures. */
class ResearchOutcomeRecoveryIT {
    @Test void terminalCheckpointSurvivesTwoLostRepliesWithoutAnotherGraphOrModelCall() throws Exception {
        var support = new ResourceTaskAllocationMigrationTest();
        try {
            support.create(); support.apply();
            var data = support.db.owner;
            ModelCallReservationStoreTest.apply79(data);
            var fixture = fixture(support.db, "a".repeat(64), Pep.AGENT_RESEARCH_RUN,
                    ResourceCallScope.requestFingerprint(ResearchRequest.canonical(ResearchOutcomeRecoveryWorker.TOPIC)));
            var entry = fixture.entry(1);
            new JdbcResourceTaskAllocations(data, ResearchOutcomeRecoveryWorker.transactions())
                    .allocate(support.candidate(fixture));
            execute(data, "create table public.research_test_provider_attempt (attempt integer generated always as identity)");

            child(support.db.name, fixture.binding().key(), "graph-crash", 77);
            assertEquals(3, count(data, "select count(*) from public.research_test_provider_attempt"));
            assertEquals(3, count(data, "select count(*) from task.model_call_reservation"));
            assertEquals(1, count(data, "select count(*) from task.resource_execution"));
            var outcomes = new JdbcResearchOutcomes(data, ResearchOutcomeRecoveryWorker.transactions(), "a".repeat(64));
            assertTrue(outcomes.find(entry).isEmpty(), "the graph worker died before association");

            // Historical facts remain recoverable after cancellation; they grant no authority to run or release output.
            execute(data, "update task.instance set status='CANCEL_REQUESTED' where id='" + fixture.row().id() + "'");
            child(support.db.name, fixture.binding().key(), "associate-crash", 77);
            var retained = outcomes.find(entry).orElseThrow();
            assertEquals(retained, outcomes.verify(entry));
            child(support.db.name, fixture.binding().key(), "recover", 0);
            child(support.db.name, fixture.binding().key(), "recover", 0);
            assertEquals(retained, outcomes.find(entry).orElseThrow());
            assertEquals(1, count(data, "select count(*) from task.resource_execution_outcome"));
            assertEquals(3, count(data, "select count(*) from public.research_test_provider_attempt"));
            assertEquals(3, count(data, "select count(*) from task.model_call_reservation"));
            assertEquals(1, count(data, "select count(*) from audit.event where event_type='resource.execution.entered'"));

            try (var connection = data.getConnection(); var update = connection.prepareStatement("""
                    update public.lg4jcheckpoint set state_data=(select state_data from public.lg4jcheckpoint
                        where thread_id=? and node_id='plan' limit 1) where checkpoint_id=?
                    """)) {
                update.setObject(1, retained.runId()); update.setObject(2, retained.checkpointId());
                assertEquals(1, update.executeUpdate());
            }
            child(support.db.name, fixture.binding().key(), "changed-refused", 0);
            assertEquals(retained, outcomes.find(entry).orElseThrow(), "changed checkpoint must not rewrite history");
            assertEquals(3, count(data, "select count(*) from public.research_test_provider_attempt"));
        } finally { support.close(); }
    }

    private static void child(String database, String binding, String phase, int expectedExit) throws Exception {
        Path log = Path.of("target", "process-logs", "research-outcome-" + database + "-" + phase + ".log");
        Files.createDirectories(log.getParent());
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // The application test classpath exceeds Windows' command-line limit. Java's argument file also works on Linux.
        Path arguments = Files.createTempFile(log.getParent(), "research-worker-", ".args").toAbsolutePath();
        Process process = null;
        try {
            Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n"
                    + ResearchOutcomeRecoveryWorker.class.getName() + "\n" + database + "\n" + binding + "\n" + phase + "\n");
            process = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + arguments)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), () -> "research worker timed out: " + log);
            assertEquals(expectedExit, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception failure) { return log.toString(); }
            });
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
            Files.deleteIfExists(arguments);
        }
    }
}
