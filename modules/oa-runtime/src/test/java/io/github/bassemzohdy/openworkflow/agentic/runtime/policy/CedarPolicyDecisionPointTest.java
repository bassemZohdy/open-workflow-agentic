package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.AGENT;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.OPERATOR;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.USER;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.V1;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.V2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.CedarPolicyDecisionPoint.RefreshOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Cedar adapter: attribution, default deny, forbid precedence, fail-closed errors, rejection and revocation. */
class CedarPolicyDecisionPointTest {

    /** A permit that errors at evaluation time. Cedar would skip it, so the adapter must refuse instead. */
    static final String BROKEN_PERMIT = """
            @id("errors.permit-broken")
            permit (principal, action, resource)
            when { context.doesNotExist == 1 };
            """;

    private CedarPolicyDecisionPoint pdp;

    @TempDir
    Path dir;

    @BeforeEach
    void loadReferenceSet() {
        pdp = new CedarPolicyDecisionPoint();
        pdp.install(PolicyFixtures.published(V1, PolicyFixtures.referenceSet()));
    }

    static AuthorizationRequest toolCall(String tool, Set<String> scopes, Long budget, boolean permitted,
                                         RiskTier tier) {
        return new AuthorizationRequest(AGENT, ActionKind.TOOL_CALL, "tool:" + tool, TASK_ID,
                new PolicyContext(scopes, tier, budget, USER, null, permitted));
    }

    static AuthorizationRequest goodSearch() {
        return toolCall("search", Set.of("cases:read"), 5L, true, RiskTier.LOW);
    }

    static AuthorizationRequest plain(Subject subject, ActionKind kind, String resource, Set<String> scopes) {
        return new AuthorizationRequest(subject, kind, resource, TASK_ID,
                new PolicyContext(scopes, null, null, null, null, null));
    }

    @Test
    void noSnapshotLoadedIsAnError() {
        CedarPolicyDecisionPoint empty = new CedarPolicyDecisionPoint();
        Decision d = empty.decide(goodSearch());
        assertEquals(DecisionOutcome.ERROR, d.outcome());
        assertFalse(d.allows());
        assertTrue(empty.activeSnapshot().isEmpty());
    }

    @Test
    void permitIsAttributedToItsStableIdAndTheSnapshotVersion() {
        Decision d = pdp.decide(goodSearch());
        assertEquals(DecisionOutcome.ALLOW, d.outcome());
        assertEquals(CedarPolicyDecisionPoint.DECIDED_BY, d.decidedBy());
        assertEquals(new PolicyRef("tools.permit-scoped-call", V1), d.policy());
    }

    @Test
    void forbidOverridesPermitAndNamesTheForbidById() {
        Decision d = pdp.decide(toolCall("search", Set.of("cases:read"), 5L, true, RiskTier.HIGH));
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertEquals(new PolicyRef("tools.forbid-high-risk-call", V1), d.policy());
    }

    @Test
    void noMatchingPolicyIsDefaultDenyWithItsOwnId() {
        Decision d = pdp.decide(toolCall("search", Set.of("cases:read"), 5L, false, RiskTier.LOW));
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertEquals(new PolicyRef(CedarPolicyDecisionPoint.DEFAULT_DENY_POLICY, V1), d.policy());
    }

    @Test
    void missingScopeSpentBudgetOrUnknownBudgetIsDenied() {
        assertEquals(DecisionOutcome.DENY,
                pdp.decide(toolCall("search", Set.of(), 5L, true, RiskTier.LOW)).outcome());
        assertEquals(DecisionOutcome.DENY,
                pdp.decide(toolCall("search", Set.of("cases:read"), 0L, true, RiskTier.LOW)).outcome());
        assertEquals(DecisionOutcome.DENY,
                pdp.decide(toolCall("search", Set.of("cases:read"), null, true, RiskTier.LOW)).outcome());
    }

    @Test
    void modelCallIsDecidedByItsOwnPolicy() {
        AuthorizationRequest model = new AuthorizationRequest(AGENT, ActionKind.MODEL_CALL, "model:small-fast",
                TASK_ID, new PolicyContext(Set.of(), RiskTier.LOW, 100L, USER, null, null));
        Decision d = pdp.decide(model);
        assertEquals(DecisionOutcome.ALLOW, d.outcome());
        assertEquals(new PolicyRef("models.permit-budgeted-call", V1), d.policy());
    }

    @Test
    void operatorPoliciesAllowAUserOnlyWithTheRecoveryScope() {
        Decision allowed = pdp.decide(plain(OPERATOR, ActionKind.OPERATION, "operation:cancel", Set.of("oa.recover")));
        assertEquals(DecisionOutcome.ALLOW, allowed.outcome());
        assertEquals(new PolicyRef("operations.permit-operator", V1), allowed.policy());
        assertEquals(DecisionOutcome.DENY,
                pdp.decide(plain(OPERATOR, ActionKind.OPERATION, "operation:cancel", Set.of())).outcome());
    }

    @Test
    void humanTaskAndPublicationPoliciesAreDecidedWithTheirIds() {
        Decision human = pdp.decide(plain(OPERATOR, ActionKind.HUMAN_TASK_ACTION,
                "human-task:01BX5ZZKBKACTAV9WEVGEMMVRZ:approve", Set.of("tasks:act")));
        assertEquals(new PolicyRef("human-task.permit-assignee", V1), human.policy());
        Decision publish = pdp.decide(plain(OPERATOR, ActionKind.PUBLICATION,
                "artifact:POLICY_BUNDLE/tool-calls@1.4.0#" + "a".repeat(64), Set.of("registry:publish")));
        assertEquals(new PolicyRef("publication.permit-registry-publisher", V1), publish.policy());
    }

    @Test
    void userOnlyPoliciesDoNotApplyToAgents() {
        Decision d = pdp.decide(plain(AGENT, ActionKind.PUBLICATION, "artifact:PROMPT/x@1.0.0#" + "b".repeat(64),
                Set.of("registry:publish")));
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertEquals(CedarPolicyDecisionPoint.DEFAULT_DENY_POLICY, d.policy().policyId());
    }

    @Test
    void servicePrincipalsAreTypedAndNotOperators() {
        Subject service = new Subject(SubjectKind.SERVICE, "batch-job");
        Decision d = pdp.decide(plain(service, ActionKind.OPERATION, "operation:drain", Set.of("oa.recover")));
        assertEquals(DecisionOutcome.DENY, d.outcome());
    }

    @Test
    void anEvaluationErrorIsAnErrorEvenBesideAMatchingPermit() {
        pdp.install(PolicyFixtures.published(V2, List.of(BROKEN_PERMIT, PolicyFixtures.TOOLS)));
        Decision d = pdp.decide(goodSearch());
        assertEquals(DecisionOutcome.ERROR, d.outcome(), d.reason());
        assertFalse(d.allows());
        assertEquals(V2, d.policy().policySetVersion());
    }

    @Test
    void anEmptySnapshotDeniesEverything() {
        pdp.install(PolicyFixtures.published(V2, List.of()));
        Decision d = pdp.decide(goodSearch());
        assertEquals(DecisionOutcome.DENY, d.outcome());
        assertEquals(new PolicyRef(CedarPolicyDecisionPoint.DEFAULT_DENY_POLICY, V2), d.policy());
    }

    @Test
    void aResourceWithQuotesCannotPassAsAnotherResource() {
        // The policy names one resource exactly. A crafted name that embeds Cedar syntax must not match it.
        pdp.install(PolicyFixtures.published(V2, List.of("""
                @id("resources.only-search")
                permit (principal, action, resource == Resource::"tool:search");
                """)));
        AuthorizationRequest exact = plain(AGENT, ActionKind.TOOL_CALL, "tool:search", Set.of());
        assertEquals(DecisionOutcome.ALLOW, pdp.decide(exact).outcome());
        AuthorizationRequest crafted = plain(AGENT, ActionKind.TOOL_CALL, "tool:search\" || \"x", Set.of());
        Decision d = pdp.decide(crafted);
        assertEquals(DecisionOutcome.DENY, d.outcome(), d.reason());
        assertEquals(new PolicyRef(CedarPolicyDecisionPoint.DEFAULT_DENY_POLICY, V2), d.policy());
    }

    @Test
    void stableIdsDoNotDependOnThePositionOfThePolicy() {
        List<String> reversed = new ArrayList<>(PolicyFixtures.referenceSet());
        Collections.reverse(reversed);
        pdp.install(PolicyFixtures.published(V2, reversed));
        Decision forbid = pdp.decide(toolCall("search", Set.of("cases:read"), 5L, true, RiskTier.HIGH));
        assertEquals(new PolicyRef("tools.forbid-high-risk-call", V2), forbid.policy());
        assertEquals(new PolicyRef("tools.permit-scoped-call", V2), pdp.decide(goodSearch()).policy());
    }

    @Test
    void aMalformedSnapshotIsRejectedAndThePreviousOneStaysEffective() {
        assertThrows(IllegalArgumentException.class,
                () -> pdp.install(PolicyFixtures.published(V2, List.of("permit(principal, action, resource"))));
        assertEquals(DecisionOutcome.ALLOW, pdp.decide(goodSearch()).outcome());
        assertEquals(V1, pdp.activeSnapshot().orElseThrow().versionId());
    }

    @Test
    void aRejectedSnapshotDoesNotLeakItsValidPoliciesIntoDecisions() {
        // BROKEN_PERMIT parses, but the second text does not: nothing from this snapshot may take effect.
        assertThrows(IllegalArgumentException.class,
                () -> pdp.install(PolicyFixtures.published(V2, List.of(BROKEN_PERMIT, "permit(principal,"))));
        assertEquals(DecisionOutcome.ALLOW, pdp.decide(goodSearch()).outcome());
    }

    @Test
    void aPolicyWithoutAnIdIsRejected() {
        String noId = "permit (principal, action, resource);";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> pdp.install(PolicyFixtures.published(V2, List.of(noId))));
        assertTrue(e.getMessage().contains("@id"), e.getMessage());
        assertEquals(V1, pdp.activeSnapshot().orElseThrow().versionId());
    }

    @Test
    void duplicatePolicyIdsAreRejected() {
        String first = "@id(\"dup.one\")\npermit (principal, action, resource);";
        String second = "@id(\"dup.one\")\nforbid (principal, action, resource);";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> pdp.install(PolicyFixtures.published(V2, List.of(first, second))));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void policyTemplatesAreRejected() {
        String template = "@id(\"tpl.one\")\npermit (principal == ?principal, action, resource);";
        assertThrows(IllegalArgumentException.class,
                () -> pdp.install(PolicyFixtures.published(V2, List.of(template))));
    }

    @Test
    void aPolicyIdOutsideTheFormatIsRejected() {
        String badId = "@id(\"Has Spaces\")\npermit (principal, action, resource);";
        assertThrows(IllegalArgumentException.class,
                () -> pdp.install(PolicyFixtures.published(V2, List.of(badId))));
    }

    @Test
    void aSnapshotWhoseHashDoesNotMatchIsRefused() {
        PublishedPolicySnapshot tampered = new PublishedPolicySnapshot(V2, "0".repeat(64),
                PolicyFixtures.referenceSet());
        assertThrows(IllegalArgumentException.class, () -> pdp.install(tampered));
        assertEquals(V1, pdp.activeSnapshot().orElseThrow().versionId());
    }

    @Test
    void revocationTakesEffectOnTheNextDecision() {
        assertEquals(DecisionOutcome.ALLOW, pdp.decide(goodSearch()).outcome());
        pdp.install(PolicyFixtures.published(V2, List.of(PolicyFixtures.MODELS, PolicyFixtures.OPERATIONS,
                PolicyFixtures.PUBLICATION, PolicyFixtures.HUMAN_TASKS)));
        Decision after = pdp.decide(goodSearch());
        assertEquals(DecisionOutcome.DENY, after.outcome());
        assertEquals(new PolicyRef(CedarPolicyDecisionPoint.DEFAULT_DENY_POLICY, V2), after.policy());
    }

    @Test
    void tighteningAPolicyTakesEffectOnTheNextDecision() {
        Decision before = pdp.decide(toolCall("search", Set.of("cases:read"), 5L, true, RiskTier.MEDIUM));
        assertEquals(DecisionOutcome.ALLOW, before.outcome());
        String tighter = PolicyFixtures.TOOLS.replace("riskTier == \"HIGH\"", "riskTier != \"LOW\"");
        pdp.install(PolicyFixtures.published(V2, List.of(tighter, PolicyFixtures.MODELS,
                PolicyFixtures.OPERATIONS, PolicyFixtures.PUBLICATION, PolicyFixtures.HUMAN_TASKS)));
        Decision after = pdp.decide(toolCall("search", Set.of("cases:read"), 5L, true, RiskTier.MEDIUM));
        assertEquals(DecisionOutcome.DENY, after.outcome());
        assertEquals(new PolicyRef("tools.forbid-high-risk-call", V2), after.policy());
    }

    @Test
    void refreshFromASourceAcceptsAGoodSnapshotAndReportsIt() throws IOException {
        PolicyFixtures.writeSnapshot(dir, V2, List.of(PolicyFixtures.MODELS));
        RefreshOutcome outcome = pdp.refresh(ManifestPolicySnapshotSource.ofDirectory(dir));
        assertEquals(new RefreshOutcome.Accepted(V2, PolicySnapshot.hashOf(V2, List.of(PolicyFixtures.MODELS))),
                outcome);
        assertEquals(V2, pdp.activeSnapshot().orElseThrow().versionId());
    }

    @Test
    void refreshFromASourceThatThrowsKeepsThePreviousSnapshot() {
        PolicySnapshotSource down = () -> {
            throw new IOException("control plane unreachable");
        };
        RefreshOutcome outcome = pdp.refresh(down);
        assertTrue(outcome instanceof RefreshOutcome.Rejected, outcome.toString());
        assertEquals(DecisionOutcome.ALLOW, pdp.decide(goodSearch()).outcome());
    }

    @Test
    void refreshWithAMalformedSnapshotIsRejectedWithAReason() {
        PolicySnapshotSource bad = () -> PolicyFixtures.published(V2, List.of("permit(("));
        RefreshOutcome outcome = pdp.refresh(bad);
        assertTrue(outcome instanceof RefreshOutcome.Rejected, outcome.toString());
        assertEquals(V1, pdp.activeSnapshot().orElseThrow().versionId());
    }

    @Test
    void decisionsNeverMixVersionsWhileSnapshotsAreSwapped() throws Exception {
        // Snapshot V1 allows the search; V2 denies it. Each decision names the version it was made against, so an
        // allow must name V1 and a deny must name V2. A mixed state would show as a mismatch or as an ERROR.
        List<String> denying = List.of(PolicyFixtures.MODELS, PolicyFixtures.OPERATIONS,
                PolicyFixtures.PUBLICATION, PolicyFixtures.HUMAN_TASKS);
        List<String> problems = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        List<Thread> readers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread reader = new Thread(() -> {
                while (running.get()) {
                    Decision d = pdp.decide(goodSearch());
                    String version = d.policy().policySetVersion();
                    boolean consistent = (V1.equals(version) && d.allows()) || (V2.equals(version) && !d.allows());
                    if (!consistent) {
                        problems.add(d.outcome() + " " + d.policy());
                    }
                }
            });
            readers.add(reader);
            reader.start();
        }
        for (int i = 0; i < 200; i++) {
            pdp.install(PolicyFixtures.published(V2, denying));
            pdp.install(PolicyFixtures.published(V1, PolicyFixtures.referenceSet()));
        }
        running.set(false);
        for (Thread reader : readers) {
            reader.join();
        }
        assertTrue(problems.isEmpty(), "inconsistent decisions: " + problems.subList(0, Math.min(5, problems.size())));
    }
}
