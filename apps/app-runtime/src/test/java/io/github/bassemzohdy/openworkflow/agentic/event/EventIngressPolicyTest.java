package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventIngressPolicyTest {
    @Test
    void shippedManifestHasMatchingHashAndOnlyTheEventRoleMayPassThePrivatePep() {
        CedarPolicyDecisionPoint pdp = new CedarPolicyDecisionPoint();
        assertInstanceOf(CedarPolicyDecisionPoint.RefreshOutcome.Accepted.class,
                pdp.refresh(ManifestPolicySnapshotSource.ofClasspath(getClass().getClassLoader(), "policy/events")));
        var subject = new Subject(SubjectKind.SERVICE, "orders-producer");
        assertEquals(DecisionOutcome.ALLOW, pdp.decide(new AuthorizationRequest(subject, ActionKind.DATA_ACCESS,
                "event:receive:example-hash", null, new PolicyContext(Set.of("oa.events.receive"), null, null, null, null, null))).outcome());
        assertEquals(DecisionOutcome.DENY, pdp.decide(new AuthorizationRequest(subject, ActionKind.DATA_ACCESS,
                "event:receive:example-hash", null, PolicyContext.EMPTY)).outcome());
        assertEquals(DecisionOutcome.DENY, pdp.decide(new AuthorizationRequest(subject, ActionKind.TOOL_CALL,
                "event:receive:example-hash", null, new PolicyContext(Set.of("oa.events.receive"), null, null, null, null, null))).outcome());
    }
}
