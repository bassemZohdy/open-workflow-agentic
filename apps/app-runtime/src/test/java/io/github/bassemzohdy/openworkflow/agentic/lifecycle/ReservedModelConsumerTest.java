package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

/** Fixture-only composition using real execution and reservation stores; no real provider or identity authority. */
@QuarkusTest
class ReservedModelConsumerTest {
    @Inject TransactionManager manager;
    ResourceTaskAllocationMigrationTest support;
    Fixture fixture;
    ResourceTaskAllocation candidate;
    AllocatedResourceTask task;
    JdbcResourceModelCalls reservations;
    ModelCall call;
    final AtomicInteger physicalCalls = new AtomicInteger();
    final AtomicReference<String> fingerprint = new AtomicReference<>("c".repeat(64));
    final AtomicLong price = new AtomicLong(999);
    final AtomicReference<Instant> deadline = new AtomicReference<>();
    final AtomicInteger maxTokens = new AtomicInteger();
    final AtomicReference<DecisionOutcome> outcome = new AtomicReference<>(DecisionOutcome.ALLOW);
    final AtomicLong policyBudget = new AtomicLong();
    volatile Runnable during = () -> { };
    ReservedLlmClient transport = new ReservedLlmClient() {
        @Override public String configuredModelId() { return "model"; }
        @Override public String reservedConfigurationFingerprint() { return fingerprint.get(); }
        @Override public long maximumCostMicros(String prompt, int tokens) { return price.get(); }
        @Override public String complete(String system, String user) { fail("unbounded transport"); return null; }
        @Override public String completeReserved(String prompt, int tokens, Instant due) {
            physicalCalls.incrementAndGet(); maxTokens.set(tokens); deadline.set(due); during.run(); return "private answer";
        }
    };

    @BeforeEach void create() throws Exception {
        support = new ResourceTaskAllocationMigrationTest(); support.create(); support.apply();
        ModelCallReservationStoreTest.apply79(support.db.owner);
        fixture = fixture(support.db, "a".repeat(64)); candidate = support.candidate(fixture);
        task = new JdbcResourceTaskAllocations(support.db.owner, manager).allocate(candidate);
        reservations = new JdbcResourceModelCalls(support.db.owner, manager);
        call = new ModelCall(candidate.contract().id(), "model", "private prompt", 100);
    }
    @AfterEach void close() throws Exception { if (support != null) support.close(); }

    GovernedLlmClient client() {
        var approved = new ApprovedModelContext.Approved(candidate.modelProfile().agent(), candidate.contract(), "model", 5000);
        var pep = new Pep(request -> {
            policyBudget.set(request.context().budgetRemainingMicros());
            return new Decision("fixture", outcome.get(), "fixture", new PolicyRef("fixture", "1"), Instant.now());
        }, event -> { }, Duration.ofSeconds(5), Clock.systemUTC());
        return new GovernedLlmClient(transport, pep, () -> Optional.of(approved), reservations);
    }
    interface Body { void run(JdbcResourceExecutionGate.Running running, ResourceModelCall node); }
    void inExecution(Body body) {
        var entry = fixture.entry(1); var executor = UUID.randomUUID();
        var fence = JdbcResourceExecutionGate.CredentialFence.pair(fixture.binding(), expected -> { });
        try (var gate = new JdbcResourceExecutionGate(support.db.owner, 1)) {
            var result = gate.openGoverned(fixture.binding(), entry, executor, fence).executeGoverned(running -> {
                body.run(running, ResourceModelCall.of(task, entry, executor, "graph/model/1", call)); return null;
            });
            assertEquals(JdbcResourceExecutionGate.Kind.CALLED, result.kind());
        }
    }
    long held() throws Exception {
        try(var c=support.db.owner.getConnection()) {
            return Long.parseLong(ResourceTaskAllocationMigrationTest.scalar(c,
                    "select coalesce(sum(held_micros),0)::text from task.model_call_reservation"));
        }
    }

    @Test void reservationPrecedesAuditedSingleBoundedAttemptAndCannotBeReplayed() throws Exception {
        var client = client(); var node = new AtomicReference<ResourceModelCall>();
        inExecution((running, occurrence) -> {
            node.set(occurrence);
            assertEquals("private answer", client.completeReserved(running, task, occurrence, call));
            assertThrows(ModelCallAlreadyReservedException.class, () -> client.completeReserved(running, task, occurrence, call));
        });
        var retained = reservations.find(node.get()).orElseThrow();
        assertEquals(1000, held()); assertEquals(1000, policyBudget.get());
        assertEquals(100, maxTokens.get()); assertEquals(retained.callDeadline(), deadline.get());
        assertEquals(1, physicalCalls.get());
    }

    @Test void mismatchedTransportOrPriceRefusesBeforeReservation() throws Exception {
        var client = client();
        inExecution((running, node) -> {
            var forged = new ResourceModelCall(node.entry(), node.executor(), node.nodeOccurrence(), "e".repeat(64), node.maxOutputTokens());
            assertThrows(ModelCallRefused.class, () -> client.completeReserved(running, task, forged, call));
            fingerprint.set("d".repeat(64));
            assertThrows(ModelCallRefused.class, () -> client.completeReserved(running, task, node, call));
            fingerprint.set("c".repeat(64)); price.set(1001);
            assertThrows(ModelCallRefused.class, () -> client.completeReserved(running, task, node, call));
        });
        assertEquals(0, held()); assertEquals(0, physicalCalls.get());
    }

    @Test void deniedPolicyRetainsTheReservationWithoutCallingTheProvider() throws Exception {
        outcome.set(DecisionOutcome.DENY); var client = client();
        inExecution((running, node) -> assertThrows(PolicyDenied.class,
                () -> client.completeReserved(running, task, node, call)));
        assertEquals(1000, held()); assertEquals(0, physicalCalls.get());
    }

    @Test void authorityLostDuringProviderWithholdsAnswerAndKeepsReservation() throws Exception {
        var client = client();
        assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> inExecution((running, node) -> {
            during = running::close;
            assertThrows(JdbcResourceExecutionGate.RefusedException.class,
                    () -> client.completeReserved(running, task, node, call));
        }));
        assertEquals(1000, held()); assertEquals(1, physicalCalls.get());
    }
}
