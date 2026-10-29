package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep.InstanceApi;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.InMemoryAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDoubles.ScriptedEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The instance API routes (WP-103) at the policy enforcement point, on the real bundled operations snapshot: start and
 * re-authorize need oa.operate, read needs oa.inspect, nothing else (not oa.recover, not the other operator scopes)
 * opens them, and an unavailable engine or audit sink refuses all three, because safe recovery names none of them.
 */
class PepInstanceApiTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);
    private static final Ulid INSTANCE = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final Subject USER = new Subject(SubjectKind.USER, "alice");

    private static CedarPolicyDecisionPoint bundled() {
        CedarPolicyDecisionPoint cedar = new CedarPolicyDecisionPoint();
        CedarPolicyDecisionPoint.RefreshOutcome outcome = cedar.refresh(ManifestPolicySnapshotSource.ofClasspath(
                Thread.currentThread().getContextClassLoader(), "policy/operations"));
        assertTrue(outcome instanceof CedarPolicyDecisionPoint.RefreshOutcome.Accepted, outcome.toString());
        return cedar;
    }

    private static Pep real(InMemoryAuditSink audit) {
        return new Pep(bundled(), audit, Duration.ofSeconds(5), CLOCK);
    }

    @Test
    void theBundledSnapshotIsTheVersionThisChangePins() {
        CedarPolicyDecisionPoint cedar = new CedarPolicyDecisionPoint();
        var outcome = cedar.refresh(ManifestPolicySnapshotSource.ofClasspath(
                Thread.currentThread().getContextClassLoader(), "policy/operations"));
        var accepted = (CedarPolicyDecisionPoint.RefreshOutcome.Accepted) outcome;
        assertEquals("2026-10-27.1", accepted.versionId());
    }

    @Test
    void eachRouteIsAllowedByItsOwnScopeAndNoOther() {
        Pep pep = real(new InMemoryAuditSink());
        List<String> others = List.of("oa.recover", "oa.agent.run", "oa.audit.read", "oa.audit.verify", "oa.operator");
        for (InstanceApi route : InstanceApi.values()) {
            String needed = route == InstanceApi.GET ? "oa.inspect" : "oa.operate";
            String wrong = route == InstanceApi.GET ? "oa.operate" : "oa.inspect";
            assertEquals(DecisionOutcome.ALLOW, pep.instanceApi(route, USER, Set.of(needed), INSTANCE).outcome(),
                    route.name());
            assertEquals(DecisionOutcome.ALLOW, pep.instanceApi(route, USER, Set.of(needed, "x"), null).outcome(),
                    route.name() + " without an instance id");
            assertEquals(DecisionOutcome.DENY, pep.instanceApi(route, USER, Set.of(wrong), INSTANCE).outcome(),
                    route.name());
            assertEquals(DecisionOutcome.DENY, pep.instanceApi(route, USER, Set.of(), INSTANCE).outcome(),
                    route.name());
            for (String scope : others) {
                assertEquals(DecisionOutcome.DENY, pep.instanceApi(route, USER, Set.of(scope), INSTANCE).outcome(),
                        route + " with " + scope);
            }
        }
    }

    @Test
    void anOperatorRuleDoesNotOpenTheInstanceRoutesAndTheOtherWayRound() {
        Pep pep = real(new InMemoryAuditSink());
        assertEquals(DecisionOutcome.ALLOW,
                pep.instanceOperation(Pep.InstanceOperation.INSPECT, USER, Set.of("oa.inspect"), INSTANCE).outcome());
        assertEquals(DecisionOutcome.DENY,
                pep.instanceApi(InstanceApi.START, USER, Set.of("oa.inspect"), null).outcome());
        assertEquals(DecisionOutcome.DENY,
                pep.instanceApi(InstanceApi.REAUTHORIZE, USER, Set.of("oa.inspect"), INSTANCE).outcome());
        assertEquals(DecisionOutcome.DENY,
                pep.instanceApi(InstanceApi.GET, USER, Set.of("oa.operate"), INSTANCE).outcome());
        assertEquals(DecisionOutcome.DENY,
                pep.instanceOperation(Pep.InstanceOperation.INSPECT, USER, Set.of("oa.operate"), INSTANCE).outcome());
    }

    @Test
    void theRequestIsADistinctOperationResourceAndOnlyAStartHasNoTask() {
        ScriptedEngine engine = new ScriptedEngine(PepTest::allow);
        Pep pep = new Pep(engine, new InMemoryAuditSink(), Duration.ofSeconds(5), CLOCK);
        pep.instanceApi(InstanceApi.START, USER, Set.of("oa.operate"), null);
        pep.instanceApi(InstanceApi.GET, USER, Set.of("oa.inspect"), INSTANCE);
        pep.instanceApi(InstanceApi.REAUTHORIZE, USER, Set.of("oa.operate"), INSTANCE);
        List<AuthorizationRequest> received = engine.received();
        assertEquals(List.of("instance:start", "instance:get", "instance:reauthorize"),
                received.stream().map(AuthorizationRequest::resource).toList());
        assertTrue(received.stream().allMatch(r -> r.action() == ActionKind.OPERATION));
        assertNull(received.get(0).taskId());
        assertEquals(INSTANCE, received.get(1).taskId());
        assertEquals(INSTANCE, received.get(2).taskId());
    }

    @Test
    void anUnavailableEngineRefusesEveryRouteEvenWithTheRecoveryScope() {
        Pep pep = new Pep(new ScriptedEngine(PepTest::error), new InMemoryAuditSink(), Duration.ofSeconds(5), CLOCK);
        for (InstanceApi route : InstanceApi.values()) {
            Decision decision = pep.instanceApi(route, USER, Set.of("oa.operate", "oa.inspect", "oa.recover"), INSTANCE);
            assertEquals(DecisionOutcome.ERROR, decision.outcome(), route.name());
            assertTrue(!decision.allows());
        }
    }

    @Test
    void anAuditSinkThatFailsRefusesEveryRouteAndAllowsNothing() {
        Pep pep = new Pep(bundled(), event -> {
            throw new IllegalStateException("audit down");
        }, Duration.ofSeconds(5), CLOCK);
        for (InstanceApi route : InstanceApi.values()) {
            Decision decision = pep.instanceApi(route, USER, Set.of("oa.operate", "oa.inspect"), INSTANCE);
            assertEquals(DecisionOutcome.ERROR, decision.outcome(), route.name());
        }
    }

    @Test
    void everyDecisionIsAudited() {
        InMemoryAuditSink audit = new InMemoryAuditSink();
        Pep pep = real(audit);
        pep.instanceApi(InstanceApi.START, USER, Set.of("oa.operate"), null);
        pep.instanceApi(InstanceApi.GET, USER, Set.of(), INSTANCE);
        assertEquals(2, audit.records().size());
    }
}
