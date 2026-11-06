package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.*;

/** Actual graph, checkpoint store and reservations; caller, policy and provider are explicit fixtures. */
@QuarkusTest
class GovernedResearchGraphTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    ResourceTaskAllocationMigrationTest support;
    Fixture fixture;
    AllocatedResourceTask task;
    ResearchAgent agent;
    final AtomicInteger calls = new AtomicInteger();
    volatile Runnable during = () -> { };
    private static final String TOPIC = "approved research";

    @BeforeEach void create() throws Exception {
        support = new ResourceTaskAllocationMigrationTest(); support.create(); support.apply();
        ModelCallReservationStoreTest.apply79(support.db.owner);
        fixture = fixture(support.db, "a".repeat(64), Pep.AGENT_RESEARCH_RUN, ResourceCallScope.requestFingerprint(ResearchRequest.canonical(TOPIC)));
        var candidate = support.candidate(fixture);
        task = new JdbcResourceTaskAllocations(support.db.owner, manager).allocate(candidate);
        var approved = new ApprovedModelContext.Approved(candidate.modelProfile().agent(), candidate.contract(), "model", 5000);
        ApprovedModelContext context = () -> Optional.of(approved);
        var pep = new Pep(request -> new Decision("fixture", DecisionOutcome.ALLOW, "fixture",
                new PolicyRef("fixture", "1"), Instant.now()), event -> { }, Duration.ofSeconds(5), Clock.systemUTC());
        ReservedLlmClient provider = new ReservedLlmClient() {
            public String configuredModelId() { return "model"; }
            public String reservedConfigurationFingerprint() { return "c".repeat(64); }
            public long maximumCostMicros(String prompt, int tokens) { return 999; }
            public String complete(String system, String user) { throw new AssertionError("unreserved model call"); }
            public String completeReserved(String prompt, int tokens, Instant deadline) {
                calls.incrementAndGet(); during.run(); return "{\"text\":\"answer\",\"actions\":[],\"escalation\":null}";
            }
        };
        var client = new GovernedLlmClient(provider, pep, context, new JdbcResourceModelCalls(support.db.owner, manager));
        agent = ResearchAgentTestFactory.create(support.db.owner, client, context);
    }

    @AfterEach void close() throws Exception { if (support != null) support.close(); }

    @Test void realGraphUsesThreeReservedNodesAndAnExactEntryCannotRunAgain() throws Exception {
        var entry = fixture.entry(1); var executor = UUID.randomUUID();
        var central = new AuditAppendOnce(support.db.owner);
        try (var gate = new JdbcResourceExecutionGate(support.db.owner, manager, 1,
                JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource(), central::appendOnce)) {
            var fence = JdbcResourceExecutionGate.CredentialFence.pair(fixture.binding(), ignored -> { });
            var result = gate.openGoverned(fixture.binding(), entry, executor, fence)
                    .executeGoverned(running -> agent.runGoverned(running, task, TOPIC));
            assertEquals("answer", result.value().text(ResearchState.REVIEW).orElseThrow());
            assertEquals(3, calls.get());
            assertEquals(3, count(support.db.owner, "select count(*) from task.model_call_reservation"));
            assertEquals(1, count(support.db.owner, "select count(*) from audit.event where event_type='resource.execution.entered'"));
            var outcomes = new JdbcResearchOutcomes(support.db.owner, manager, "a".repeat(64));
            caller.begin();
            ResourceExecutionOutcome outcome;
            try { outcome = outcomes.associate(entry); }
            finally { caller.rollback(); }
            assertEquals(outcome, outcomes.associate(entry));
            assertEquals(outcome, outcomes.verify(entry));
            assertEquals(outcome, outcomes.find(entry).orElseThrow());
            assertThrows(IllegalStateException.class, () -> new JdbcResearchOutcomes(support.db.owner, manager, "d".repeat(64)).verify(entry));
            var retry = gate.openGoverned(fixture.binding(), entry, UUID.randomUUID(), fence)
                    .executeGoverned(running -> { fail("retained entry re-ran the graph"); return null; });
            assertEquals(JdbcResourceExecutionGate.Kind.RETAINED, retry.kind());
            assertEquals(3, calls.get());
            try (var c = support.db.owner.getConnection(); var q = c.prepareStatement("""
                    update public.lg4jcheckpoint set state_data=(select state_data from public.lg4jcheckpoint
                        where thread_id=? and node_id='plan' limit 1) where checkpoint_id=?
                    """)) {
                q.setObject(1, outcome.runId()); q.setObject(2, outcome.checkpointId()); assertEquals(1, q.executeUpdate());
            }
            assertEquals(outcome, outcomes.find(entry).orElseThrow(), "historical association must not be rewritten");
            assertThrows(IllegalStateException.class, () -> outcomes.verify(entry));
            assertThrows(IllegalStateException.class, () -> outcomes.associate(entry));
        }
    }

    @Test void changedRequestRefusesBeforeTheProvider() throws Exception {
        try (var gate = new JdbcResourceExecutionGate(support.db.owner, manager, 1)) {
            var fence = JdbcResourceExecutionGate.CredentialFence.pair(fixture.binding(), ignored -> { });
            assertThrows(ModelCallRefused.class, () -> gate.openGoverned(fixture.binding(), fixture.entry(1), UUID.randomUUID(), fence)
                    .executeGoverned(running -> agent.runGoverned(running, task, "changed")));
            assertEquals(0, calls.get());
            assertEquals(0, count(support.db.owner, "select count(*) from task.model_call_reservation"));
        }
    }

    @Test void preexistingCheckpointRequiresReconciliationBeforeAnyProviderCall() throws Exception {
        var saver = PostgresSaver.builder().datasource(support.db.owner).createTables(false)
                .stateSerializer(new ObjectStreamStateSerializer<>(ResearchState::new)).build();
        saver.put(RunnableConfig.builder().threadId(fixture.entry(1).threadId().value()).build(),
                Checkpoint.builder().state(Map.of(ResearchState.TOPIC, TOPIC)).nodeId("plan").nextNodeId("draft").build());
        try (var gate = new JdbcResourceExecutionGate(support.db.owner, manager, 1)) {
            var fence = JdbcResourceExecutionGate.CredentialFence.pair(fixture.binding(), ignored -> { });
            assertThrows(ModelCallRefused.class, () -> gate.openGoverned(fixture.binding(), fixture.entry(1), UUID.randomUUID(), fence)
                    .executeGoverned(running -> agent.runGoverned(running, task, TOPIC)));
            assertEquals(0, calls.get());
            assertEquals(0, count(support.db.owner, "select count(*) from task.model_call_reservation"));
        }
    }

    @Test void controlLossDuringProviderWithholdsGraphOutputAndStopsLaterNodes() throws Exception {
        try (var gate = new JdbcResourceExecutionGate(support.db.owner, manager, 1)) {
            var fence = JdbcResourceExecutionGate.CredentialFence.pair(fixture.binding(), ignored -> { });
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var current = new AtomicReference<JdbcResourceExecutionGate.Running>();
            var worker = Executors.newSingleThreadExecutor();
            try {
                during = () -> {
                    entered.countDown();
                    try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("provider timed out"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                };
                var pending = worker.submit(() -> gate.openGoverned(fixture.binding(), fixture.entry(1), UUID.randomUUID(), fence)
                        .executeGoverned(running -> { current.set(running); return agent.runGoverned(running, task, TOPIC); }));
                assertTrue(entered.await(20, TimeUnit.SECONDS));
                current.get().close();
                assertEquals(1, gate.activeCount(), "the in-flight provider must retain its gate slot");
                release.countDown();
                assertThrows(ExecutionException.class, () -> pending.get(20, TimeUnit.SECONDS));
                assertEquals(1, calls.get());
                assertEquals(1, count(support.db.owner, "select count(*) from task.model_call_reservation"));
                var outcomes = new JdbcResearchOutcomes(support.db.owner, manager, "a".repeat(64));
                assertThrows(IllegalStateException.class, () -> outcomes.associate(fixture.entry(1)));
                assertEquals(0, count(support.db.owner, "select count(*) from task.resource_execution_outcome"));
            } finally { release.countDown(); worker.shutdownNow(); }
        }
    }

    @Test void interruptedGraphWaiterRetainsTheProviderUntilItReallyReturns() throws Exception {
        try (var gate = new JdbcResourceExecutionGate(support.db.owner, manager, 1)) {
            var fence = JdbcResourceExecutionGate.CredentialFence.pair(fixture.binding(), ignored -> { });
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>(); var interrupted = new AtomicBoolean();
            during = () -> {
                entered.countDown();
                try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("provider timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            };
            var worker = Thread.ofVirtual().start(() -> {
                try { gate.openGoverned(fixture.binding(), fixture.entry(1), UUID.randomUUID(), fence)
                        .executeGoverned(running -> agent.runGoverned(running, task, TOPIC)); }
                catch (Throwable caught) { failure.set(caught); }
                finally { interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                assertTrue(entered.await(20, TimeUnit.SECONDS));
                worker.interrupt();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waitingForPhysicalExit = false;
                while (worker.isAlive() && System.nanoTime() < deadline && !waitingForPhysicalExit) {
                    waitingForPhysicalExit = Arrays.stream(worker.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().endsWith("NodeActivityTracker") && frame.getMethodName().equals("closeAndAwait"));
                    if (!waitingForPhysicalExit) Thread.sleep(10);
                }
                assertTrue(waitingForPhysicalExit, "interrupted graph must wait for the physical provider");
                assertEquals(1, gate.activeCount(), "interruption must not release an in-flight provider");
                release.countDown(); worker.join(20_000);
                assertFalse(worker.isAlive()); assertNotNull(failure.get()); assertTrue(interrupted.get());
                assertEquals(1, calls.get());
            } finally { release.countDown(); worker.join(20_000); }
        }
    }
}
