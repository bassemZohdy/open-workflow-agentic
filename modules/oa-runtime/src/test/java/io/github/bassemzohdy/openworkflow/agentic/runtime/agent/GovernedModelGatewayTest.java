package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelResult;
import java.util.Optional;
import java.util.function.ToLongBiFunction;
import org.junit.jupiter.api.Test;

/** The ModelGateway contract over the governed boundary: sealed outcomes, zero provider calls on every refusal. */
class GovernedModelGatewayTest {
    private static final ModelCall CALL = new ModelCall(TASK.id(), "gpt-test", "hello", 100);
    private static final ToLongBiFunction<ModelCall, String> COST = (call, text) -> 42L;

    private final CountingProvider provider = new CountingProvider();
    private final Engine engine = new Engine(DecisionOutcome.ALLOW);
    private final Audit audit = new Audit();

    private GovernedModelGateway gateway(ApprovedModelContext context, ToLongBiFunction<ModelCall, String> cost,
                                         LlmClient llm) {
        return new GovernedModelGateway(new GovernedLlmClient(llm, pep(engine, audit), context), context, cost, CLOCK);
    }

    private GovernedModelGateway gateway(ApprovedModelContext context) { return gateway(context, COST, provider); }

    private ModelResult.Rejected rejected(ModelResult result) {
        return assertInstanceOf(ModelResult.Rejected.class, result);
    }

    @Test void anAuditedAllowCompletesOnceWithTheStatedCost() {
        var result = assertInstanceOf(ModelResult.Completed.class, gateway(APPROVED).complete(CALL));
        assertEquals(OK, result.text());
        assertEquals(42L, result.costMicros());
        assertEquals(1, provider.calls.get());
        assertEquals(1, engine.asked.size());
        assertFalse(audit.events.isEmpty(), "the decision was audited");
    }

    @Test void noApprovedContextRejectsWithoutReachingPolicyOrProvider() {
        var rejection = rejected(gateway(NONE).complete(CALL));
        assertFalse(rejection.decision().allows());
        assertEquals(0, provider.calls.get());
        assertTrue(engine.asked.isEmpty());
    }

    @Test void aCallForAnotherTaskCannotBorrowTheApproval() {
        var other = new ModelCall(Ulid.parse("01CP2ZZKBKACTAV9WEVGEMMVRZ"), "gpt-test", "hello", 100);
        assertTrue(rejected(gateway(APPROVED).complete(other)).decision().reason().contains("task"));
        assertEquals(0, provider.calls.get());
        assertTrue(engine.asked.isEmpty());
    }

    @Test void aCallNamingAnotherModelNeverSwitchesModels() {
        var other = new ModelCall(TASK.id(), "bigger-model", "hello", 100);
        assertTrue(rejected(gateway(APPROVED).complete(other)).decision().reason().contains("model"));
        assertEquals(0, provider.calls.get());
        assertTrue(engine.asked.isEmpty());
    }

    @Test void changedApprovalBeforePolicyCannotBeBorrowed() {
        var first = APPROVED.current().orElseThrow();
        var other = new ApprovedModelContext.Approved(AGENT, TASK, "other-model", first.budgetRemainingMicros());
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        ApprovedModelContext changing = () -> Optional.of(reads.getAndIncrement() == 0 ? first : other);
        rejected(gateway(changing).complete(CALL));
        assertTrue(engine.asked.isEmpty());
        assertEquals(0, provider.calls.get());
    }

    @Test void approvalLostDuringPolicyAuditCannotReachTheProvider() {
        var first = APPROVED.current().orElseThrow();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        ApprovedModelContext changing = () -> reads.getAndIncrement() < 2 ? Optional.of(first) : Optional.empty();
        rejected(gateway(changing).complete(CALL));
        assertEquals(1, engine.asked.size());
        assertFalse(audit.events.isEmpty());
        assertEquals(0, provider.calls.get());
    }

    @Test void approvalLostDuringProviderCallWithholdsTheModelOutput() {
        var current = new java.util.concurrent.atomic.AtomicReference<>(APPROVED.current());
        ApprovedModelContext context = current::get;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient providerThatLosesApproval = (system, user) -> {
            calls.incrementAndGet(); current.set(Optional.empty()); return "private model output";
        };
        var result = assertInstanceOf(ModelResult.Failed.class, gateway(context, COST, providerThatLosesApproval).complete(CALL));
        assertEquals(1, calls.get(), "an already-started provider call cannot be undone");
        assertFalse(result.reason().contains("private model output"));
    }

    @Test void unavailableApprovalSourceReportsWhetherTheProviderWasCalled() {
        var first = APPROVED.current().orElseThrow();
        for (int failureAt : new int[] {1, 2, 3, 4}) {
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            ApprovedModelContext context = () -> {
                if (reads.incrementAndGet() == failureAt) throw new IllegalStateException("private detail");
                return Optional.of(first);
            };
            LlmClient counting = (system, user) -> { calls.incrementAndGet(); return "private output"; };
            var result = gateway(context, COST, counting).complete(CALL);
            if (failureAt < 4) {
                var refused = assertInstanceOf(ModelResult.Rejected.class, result);
                assertFalse(refused.decision().reason().contains("private detail"));
                assertEquals(0, calls.get());
            } else {
                var failed = assertInstanceOf(ModelResult.Failed.class, result);
                assertFalse(failed.reason().contains("private output"));
                assertEquals(1, calls.get());
            }
        }
    }

    @Test void aPolicyDenyIsRejectedCarryingTheRefusingDecision() {
        var denying = new Engine(DecisionOutcome.DENY);
        var gateway = new GovernedModelGateway(new GovernedLlmClient(provider, pep(denying, audit), APPROVED), APPROVED,
                COST, CLOCK);
        var rejection = rejected(gateway.complete(CALL));
        assertEquals(DecisionOutcome.DENY, rejection.decision().outcome());
        assertEquals("test-engine", rejection.decision().decidedBy());
        assertEquals(0, provider.calls.get());
    }

    @Test void anAuditFailureTurnsIntoARejectionNotACall() {
        audit.failing = true;
        assertFalse(rejected(gateway(APPROVED).complete(CALL)).decision().allows());
        assertEquals(0, provider.calls.get());
    }

    @Test void aZeroBudgetIsRejectedBeforePolicy() {
        ApprovedModelContext broke = () -> Optional.of(new ApprovedModelContext.Approved(AGENT, TASK, "gpt-test", 0L));
        rejected(gateway(broke).complete(CALL));
        assertEquals(0, provider.calls.get());
        assertTrue(engine.asked.isEmpty());
    }

    @Test void aProviderFailureIsFailedAndNeverEchoesTheProviderMessage() {
        LlmClient broken = (system, user) -> { throw new IllegalStateException("secret prompt text hello"); };
        var failed = assertInstanceOf(ModelResult.Failed.class, gateway(APPROVED, COST, broken).complete(CALL));
        assertFalse(failed.reason().contains("secret"));
    }

    @Test void providerExceptionsCannotMasqueradeAsPreCallRefusals() {
        var denial = new io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision("provider", DecisionOutcome.DENY,
                "private provider detail", io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef.FAIL_CLOSED, CLOCK.instant());
        for (RuntimeException error : java.util.List.of(new ModelCallRefused("private provider detail"),
                new io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied(denial))) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            LlmClient throwing = (system, user) -> { calls.incrementAndGet(); throw error; };
            var failed = assertInstanceOf(ModelResult.Failed.class, gateway(APPROVED, COST, throwing).complete(CALL));
            assertEquals(1, calls.get());
            assertFalse(failed.reason().contains("private"));
        }
    }

    @Test void anUndeterminedOrNegativeCostIsFailedNotCompletedAtZero() {
        assertInstanceOf(ModelResult.Failed.class,
                gateway(APPROVED, (c, t) -> { throw new IllegalStateException("no usage"); }, provider).complete(CALL));
        assertInstanceOf(ModelResult.Failed.class, gateway(APPROVED, (c, t) -> -1L, provider).complete(CALL));
    }

    @Test void constructionAndInputAreNullChecked() {
        var governed = new GovernedLlmClient(provider, pep(engine, audit), APPROVED);
        assertThrows(NullPointerException.class, () -> new GovernedModelGateway(null, APPROVED, COST, CLOCK));
        assertThrows(NullPointerException.class, () -> new GovernedModelGateway(governed, null, COST, CLOCK));
        assertThrows(NullPointerException.class, () -> new GovernedModelGateway(governed, APPROVED, null, CLOCK));
        assertThrows(NullPointerException.class, () -> new GovernedModelGateway(governed, APPROVED, COST, null));
        assertThrows(NullPointerException.class, () -> gateway(APPROVED).complete(null));
    }
}
