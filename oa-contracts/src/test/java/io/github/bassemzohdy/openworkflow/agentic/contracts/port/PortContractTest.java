package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.CHILD_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.NOW;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TENANT;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TOOL;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.USER;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.WORKFLOW_INSTANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.doubles.GuardedToolGateway;
import io.github.bassemzohdy.openworkflow.agentic.contracts.doubles.InMemoryArtifactRegistry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.doubles.InMemoryEffectLedger;
import io.github.bassemzohdy.openworkflow.agentic.contracts.doubles.InMemoryTaskStore;
import io.github.bassemzohdy.openworkflow.agentic.contracts.doubles.ScriptedPolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectKey;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Each port is implemented by a test double; the tests state the failure semantics the Javadoc promises. */
class PortContractTest {

    private static final ThreadId THREAD = ThreadId.derive(TENANT, USER.id(), WORKFLOW_INSTANCE);

    private static Effect intent(String node, String key, String target) {
        return new Effect(THREAD, node, key, target, EffectState.INTENDED, null, NOW);
    }

    // EffectLedger

    @Test
    void recordingTheSameIntentTwiceReturnsTheStoredEffect() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        Effect first = ledger.recordIntent(intent("draft", "k1", "case-system:update"));
        assertEquals(first, ledger.recordIntent(intent("draft", "k1", "case-system:update")));
    }

    @Test
    void anIdempotencyKeyCannotBeReusedForAnotherTarget() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        ledger.recordIntent(intent("draft", "k1", "case-system:update"));
        assertThrows(IllegalStateException.class,
                () -> ledger.recordIntent(intent("draft", "k1", "payments:refund")));
    }

    @Test
    void anIntentMustStartIntended() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        Effect confirmed = new Effect(THREAD, "draft", "k1", "t", EffectState.CONFIRMED, null, NOW);
        assertThrows(IllegalArgumentException.class, () -> ledger.recordIntent(confirmed));
    }

    @Test
    void anOutcomeMovesTheEffectAndIllegalMovesAreRefused() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        Effect e = ledger.recordIntent(intent("draft", "k1", "case-system:update"));
        EffectKey key = e.key();
        assertEquals(EffectState.CONFIRMED, ledger.recordOutcome(key, EffectState.CONFIRMED, "201").state());
        assertEquals(EffectState.CONFIRMED, ledger.recordOutcome(key, EffectState.CONFIRMED, "201").state(),
                "repeating the same outcome is idempotent");
        assertThrows(IllegalStateException.class, () -> ledger.recordOutcome(key, EffectState.FAILED, "late"));
    }

    @Test
    void anOutcomeForAnUnknownKeyIsRefused() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        EffectKey unknown = new EffectKey(THREAD, "draft", "nope");
        assertThrows(NoSuchElementException.class,
                () -> ledger.recordOutcome(unknown, EffectState.CONFIRMED, null));
    }

    @Test
    void unresolvedIntentsAreFoundPerThreadAndOnlyWhileIntended() {
        InMemoryEffectLedger ledger = new InMemoryEffectLedger();
        Effect open = ledger.recordIntent(intent("draft", "k1", "case-system:update"));
        Effect done = ledger.recordIntent(intent("draft", "k2", "case-system:update"));
        ledger.recordOutcome(done.key(), EffectState.CONFIRMED, null);
        ThreadId other = ThreadId.derive("other", USER.id(), WORKFLOW_INSTANCE);
        ledger.recordIntent(new Effect(other, "draft", "k3", "t", EffectState.INTENDED, null, NOW));

        assertEquals(List.of(open), ledger.findUnresolved(THREAD));
        ledger.recordOutcome(open.key(), EffectState.FAILED, "timeout");
        assertTrue(ledger.findUnresolved(THREAD).isEmpty(), "an empty list means nothing to reconcile");
    }

    // TaskStore

    @Test
    void taskStoreFindsByIdAndByThreadAndRefusesDuplicates() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        assertThrows(IllegalStateException.class, () -> store.insert(new AgentTaskRecord(TASK_ID, TENANT, USER,
                WORKFLOW_INSTANCE, THREAD, Samples.contract(), AgentTaskStatus.PENDING, 1L)),
                "a new task must start at version 0");
        store.insert(new AgentTaskRecord(TASK_ID, TENANT, USER, WORKFLOW_INSTANCE, THREAD, Samples.contract(),
                AgentTaskStatus.PENDING, 0L));
        assertTrue(store.find(TASK_ID).isPresent());
        assertEquals(TASK_ID, store.findByThread(THREAD).orElseThrow().taskId());
        // A second task for the same thread is refused by the unique constraint.
        AgentTaskContract child = Samples.contract().narrow(CHILD_ID, Set.of(), Set.of(), RiskTier.MEDIUM,
                Samples.limits());
        assertThrows(IllegalStateException.class, () -> store.insert(new AgentTaskRecord(CHILD_ID, TENANT, USER,
                WORKFLOW_INSTANCE, THREAD, child, AgentTaskStatus.PENDING, 0L)));
        assertTrue(store.findByThread(ThreadId.derive("unknown", USER.id(), WORKFLOW_INSTANCE)).isEmpty(),
                "an unknown thread is absent, never created");
    }

    @Test
    void taskStoreReplaceIsOptimistic() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        AgentTaskRecord v0 = store.insert(new AgentTaskRecord(TASK_ID, TENANT, USER, WORKFLOW_INSTANCE, THREAD,
                Samples.contract(), AgentTaskStatus.PENDING, 0L));
        AgentTaskRecord v1 = new AgentTaskRecord(TASK_ID, TENANT, USER, WORKFLOW_INSTANCE, THREAD,
                Samples.contract(), AgentTaskStatus.RUNNING, 1L);
        assertEquals(AgentTaskStatus.RUNNING, store.replace(v1, v0.version()).status());
        assertThrows(IllegalStateException.class, () -> store.replace(v1, 0L), "stale version is refused");
        AgentTaskRecord skipped = new AgentTaskRecord(TASK_ID, TENANT, USER, WORKFLOW_INSTANCE, THREAD,
                Samples.contract(), AgentTaskStatus.COMPLETED, 5L);
        assertThrows(IllegalStateException.class, () -> store.replace(skipped, 1L), "versions must step by one");
    }

    // ArtifactRegistry

    @Test
    void registryReturnsTheActiveVersionOrNothingNeverAGuess() {
        InMemoryArtifactRegistry registry = new InMemoryArtifactRegistry();
        registry.publish(ref("1.3.0"), false);
        assertTrue(registry.activeVersion(ArtifactKind.PROMPT, "triage").isEmpty(),
                "a version that is not approved is not active");
        registry.publish(ref("1.4.0"), true);
        assertEquals("1.4.0", registry.activeVersion(ArtifactKind.PROMPT, "triage").orElseThrow().version());
        assertEquals("1.3.0", registry.version(ArtifactKind.PROMPT, "triage", "1.3.0").orElseThrow().version(),
                "a pinned run can still find its older version");
        assertTrue(registry.version(ArtifactKind.PROMPT, "triage", "9.9.9").isEmpty());
    }

    // ToolGateway (reference implementation over the ports)

    @Test
    void aToolOutsideTheContractIsRejectedAndNotCalled() {
        InMemoryTaskStore store = storeWithTask();
        AtomicInteger calls = new AtomicInteger();
        ToolGateway gateway = new GuardedToolGateway(store, r -> allowDecision(),
                c -> {
                    calls.incrementAndGet();
                    return "x";
                });
        ToolResult result = gateway.invoke(new ToolCall(TASK_ID, "tool:payments", "{}"));
        assertInstanceOf(ToolResult.Rejected.class, result);
        assertEquals(0, calls.get());
    }

    @Test
    void aDenyFromThePolicyStopsTheCall() {
        InMemoryTaskStore store = storeWithTask();
        AtomicInteger calls = new AtomicInteger();
        ScriptedPolicyDecisionPoint pdp = new ScriptedPolicyDecisionPoint(r -> Samples.decision());
        ToolGateway gateway = new GuardedToolGateway(store, pdp, c -> {
            calls.incrementAndGet();
            return "x";
        });
        ToolResult result = gateway.invoke(new ToolCall(TASK_ID, TOOL, "{}"));
        assertEquals(DecisionOutcome.DENY, ((ToolResult.Rejected) result).decision().outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void aBrokenPolicyEngineRefusesThroughTheFailClosedDecorator() {
        InMemoryTaskStore store = storeWithTask();
        AtomicInteger calls = new AtomicInteger();
        PolicyDecisionPoint broken = new FailClosedPolicyDecisionPoint(r -> {
            throw new IllegalStateException("no policy loaded");
        }, Duration.ofSeconds(2));
        ToolGateway gateway = new GuardedToolGateway(store, broken, c -> {
            calls.incrementAndGet();
            return "x";
        });
        ToolResult result = gateway.invoke(new ToolCall(TASK_ID, TOOL, "{}"));
        Decision refusal = ((ToolResult.Rejected) result).decision();
        assertEquals(DecisionOutcome.ERROR, refusal.outcome());
        assertEquals(0, calls.get(), "an error in the policy never reaches the tool");
    }

    @Test
    void anAllowedCallCompletesAndAToolCrashIsAFailureThatMayHaveHadAnEffect() {
        InMemoryTaskStore store = storeWithTask();
        ToolGateway ok = new GuardedToolGateway(store, r -> allowDecision(), c -> "found 3 cases");
        assertEquals("found 3 cases",
                ((ToolResult.Completed) ok.invoke(new ToolCall(TASK_ID, TOOL, "{}"))).output());

        ToolGateway crashing = new GuardedToolGateway(store, r -> allowDecision(), c -> {
            throw new IllegalStateException("connection reset after the request was sent");
        });
        ToolResult.Failed failed = (ToolResult.Failed) crashing.invoke(new ToolCall(TASK_ID, TOOL, "{}"));
        assertTrue(failed.effectMayHaveHappened());
    }

    @Test
    void anUnknownTaskIsRejected() {
        ToolGateway gateway = new GuardedToolGateway(new InMemoryTaskStore(), r -> allowDecision(), c -> "x");
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(new ToolCall(TASK_ID, TOOL, "{}")));
    }

    // Result types

    @Test
    void aRejectionNeverCarriesAnAllow() {
        Decision allow = allowDecision();
        assertThrows(IllegalArgumentException.class, () -> new ToolResult.Rejected(allow));
        assertThrows(IllegalArgumentException.class, () -> new ModelResult.Rejected(allow));
        assertFalse(new ToolResult.Rejected(Samples.decision()).decision().allows());
    }

    @Test
    void modelCompletionCostIsNeverNegative() {
        assertThrows(IllegalArgumentException.class, () -> new ModelResult.Completed("t", -1L));
        assertEquals(0L, new ModelResult.Completed("t", 0L).costMicros());
    }

    @Test
    void failedResultsNeedAReason() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResult.Failed(" ", false));
        assertThrows(IllegalArgumentException.class, () -> new ModelResult.Failed(""));
    }

    @Test
    void toolCallsAndModelCallsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall(TASK_ID, "t", "x".repeat(65_537)));
        assertThrows(IllegalArgumentException.class, () -> new ModelCall(TASK_ID, "m", "p", 0));
        assertThrows(IllegalArgumentException.class, () -> new ModelCall(TASK_ID, "m", "p",
                ModelCall.MAX_OUTPUT_TOKENS + 1));
    }

    private static Decision allowDecision() {
        return new Decision("engine", DecisionOutcome.ALLOW, "permit matched", new PolicyRef("p", "1"), NOW);
    }

    private static ArtifactRef ref(String version) {
        return new ArtifactRef(ArtifactKind.PROMPT, "triage", version, "c".repeat(64));
    }

    private static InMemoryTaskStore storeWithTask() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.insert(new AgentTaskRecord(TASK_ID, TENANT, USER, WORKFLOW_INSTANCE, THREAD, Samples.contract(),
                AgentTaskStatus.RUNNING, 0L));
        return store;
    }
}
