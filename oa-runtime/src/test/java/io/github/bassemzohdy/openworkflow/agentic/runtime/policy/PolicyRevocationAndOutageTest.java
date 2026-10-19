package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.AGENT;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.OPERATOR;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.V1;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.V2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.CedarPolicyDecisionPoint.RefreshOutcome;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.InMemoryAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.SafeRecovery.RecoveryOperation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Policy revocation and fail-closed behaviour when the source of approved policy is unavailable (M12, M20, M27). The
 * source here is a snapshot folder on disk, standing in for the approved-state store of the control side; the test
 * exercises the enforcement point, the snapshot lifecycle and the real Cedar engine together.
 *
 * <p>These tests do not prove T23 as written. They have no control service, no tool gateway and no emergency
 * containment, so they are named by behaviour and are not annotated {@code @Acceptance}.
 */
class PolicyRevocationAndOutageTest {

    private static final Set<String> OPERATOR_SCOPES = Set.of(SafeRecovery.OPERATOR_SCOPE);

    @TempDir
    Path store;

    private Path snapshot() {
        return store.resolve("current");
    }

    private void publish(String version, List<String> texts) throws IOException {
        PolicyFixtures.writeSnapshot(snapshot(), version, texts);
    }

    private static Pep pep(CedarPolicyDecisionPoint engine, InMemoryAuditSink audit) {
        return new Pep(engine, audit, Duration.ofSeconds(5), Clock.fixed(PolicyFixtures.NOW, ZoneOffset.UTC));
    }

    private static AtomicInteger calls() {
        return new AtomicInteger();
    }

    @Test
    void aRevokedPolicyIsRefusedOnTheNextCallAfterTheSnapshotIsRefreshed() throws IOException {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Pep pep = pep(engine, audit);
        publish(V1, PolicyFixtures.referenceSet());
        assertTrue(engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot())) instanceof RefreshOutcome.Accepted);

        Decision before = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
        assertEquals(DecisionOutcome.ALLOW, before.outcome());

        // Revoke: publish a snapshot without the tool permit, and refresh.
        publish(V2, List.of(PolicyFixtures.MODELS, PolicyFixtures.OPERATIONS, PolicyFixtures.PUBLICATION,
                PolicyFixtures.HUMAN_TASKS));
        assertTrue(engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot())) instanceof RefreshOutcome.Accepted);

        AtomicInteger executed = calls();
        Decision after = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
        assertEquals(DecisionOutcome.DENY, after.outcome());
        assertEquals(new PolicyRef(CedarPolicyDecisionPoint.DEFAULT_DENY_POLICY, V2), after.policy());
        assertThrows(PolicyDenied.class, () -> Pep.guard(after, () -> executed.incrementAndGet()));
        assertEquals(0, executed.get());
    }

    @Test
    void aControlOutageKeepsTheLastApprovedSnapshotInForce() throws IOException {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        Pep pep = pep(engine, new InMemoryAuditSink());
        publish(V1, PolicyFixtures.referenceSet());
        engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));

        // The approved-state store disappears: the control side is gone.
        deleteRecursively(snapshot());
        RefreshOutcome outcome = engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));
        assertTrue(outcome instanceof RefreshOutcome.Rejected, outcome.toString());

        Decision d = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
        assertEquals(DecisionOutcome.ALLOW, d.outcome());
        assertEquals(new PolicyRef("tools.permit-scoped-call", V1), d.policy());
    }

    @Test
    void aRejectedRefreshNeverRollsBackAPublishedRevocation() throws IOException {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        Pep pep = pep(engine, new InMemoryAuditSink());
        publish(V1, PolicyFixtures.referenceSet());
        engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));
        publish(V2, List.of(PolicyFixtures.MODELS, PolicyFixtures.OPERATIONS, PolicyFixtures.PUBLICATION,
                PolicyFixtures.HUMAN_TASKS));
        engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));

        // The control side is now down, and whatever it publishes next is damaged.
        deleteRecursively(snapshot());
        assertTrue(engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot())) instanceof RefreshOutcome.Rejected);

        assertEquals(DecisionOutcome.DENY, pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L).outcome());
        assertEquals(V2, engine.activeSnapshot().orElseThrow().versionId());
    }

    @Test
    void aTamperedSnapshotIsRejectedAndTheLastGoodOneStays() throws IOException {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        Pep pep = pep(engine, new InMemoryAuditSink());
        publish(V1, PolicyFixtures.referenceSet());
        engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));

        // Someone widens the approved tool policy on disk without a new approval (the hash no longer matches).
        Files.writeString(snapshot().resolve("policy-0.cedar"),
                "@id(\"tools.permit-scoped-call\")\npermit (principal, action, resource);\n", StandardCharsets.UTF_8);
        RefreshOutcome outcome = engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));
        assertTrue(outcome instanceof RefreshOutcome.Rejected, outcome.toString());
        assertTrue(((RefreshOutcome.Rejected) outcome).reason().contains("hash"), outcome.toString());

        // A forbidden call stays forbidden, and an allowed one stays allowed under the approved text.
        assertEquals(DecisionOutcome.DENY, pep.toolCall(AGENT, PolicyFixtures.task(), "delete", 500L).outcome());
        assertEquals(DecisionOutcome.ALLOW, pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L).outcome());
        assertEquals(V1, engine.activeSnapshot().orElseThrow().versionId());
    }

    @Test
    void withNoGoodSnapshotEverEverythingIsRefusedAndOnlyRecoveryRemains() throws IOException {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Pep pep = pep(engine, audit);
        // The folder exists but its manifest is damaged: the source is unreadable from the start.
        Files.createDirectories(snapshot());
        Files.writeString(snapshot().resolve(ManifestPolicySnapshotSource.MANIFEST), "version=\n",
                StandardCharsets.UTF_8);
        assertTrue(engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot())) instanceof RefreshOutcome.Rejected);

        AtomicInteger executed = calls();
        Decision tool = pep.toolCall(AGENT, PolicyFixtures.task(), "search", 500L);
        assertEquals(DecisionOutcome.ERROR, tool.outcome());
        assertThrows(PolicyDenied.class, () -> Pep.guard(tool, () -> executed.incrementAndGet()));
        assertEquals(0, executed.get());

        Decision cancel = pep.operation(RecoveryOperation.CANCEL, OPERATOR, OPERATOR_SCOPES, TASK_ID);
        assertTrue(cancel.allows(), cancel.reason());
        assertFalse(pep.operation(RecoveryOperation.CANCEL, AGENT, OPERATOR_SCOPES, TASK_ID).allows());
    }

    @Test
    void duringAnOutageTheLastApprovedSnapshotDecidesEveryCall() throws IOException {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        Pep pep = pep(engine, new InMemoryAuditSink());
        publish(V1, PolicyFixtures.referenceSet());
        engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));
        deleteRecursively(snapshot());
        engine.refresh(ManifestPolicySnapshotSource.ofDirectory(snapshot()));

        // The policy still decides: an operator with the scope may pause, a model call within budget is allowed,
        // and an operation without the scope is denied by the policy itself (a DENY is not overridden by recovery).
        assertEquals(DecisionOutcome.ALLOW,
                pep.operation(RecoveryOperation.PAUSE, OPERATOR, OPERATOR_SCOPES, TASK_ID).outcome());
        assertEquals(DecisionOutcome.ALLOW,
                pep.modelCall(AGENT, PolicyFixtures.task(), "small-fast", 900L).outcome());
        assertEquals(DecisionOutcome.DENY,
                pep.operation(RecoveryOperation.CANCEL, OPERATOR, Set.of(), TASK_ID).outcome());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
