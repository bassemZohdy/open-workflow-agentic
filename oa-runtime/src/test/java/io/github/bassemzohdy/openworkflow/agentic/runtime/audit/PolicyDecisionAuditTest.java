package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDecisionRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bridge from policy decisions to audit events (WP-105d): one event per decision, decision reference included. */
class PolicyDecisionAuditTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Keeps every event the bridge appends. */
    private static final class CapturingTrail implements AuditSink {

        private final List<AuditEvent> events = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public void record(AuditEvent event) {
            if (failure != null) {
                throw failure;
            }
            events.add(event);
        }
    }

    @Test
    void aRecoveryDenyBecomesOneEventWithItsOutcomeAndItsPolicyReference() {
        CapturingTrail trail = new CapturingTrail();
        Subject operator = new Subject(SubjectKind.USER, "ops-lead");
        AuthorizationRequest request = new AuthorizationRequest(operator, ActionKind.OPERATION, "operation:retry",
                Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                new PolicyContext(Set.of("oa.recover"), null, null, null, null, null));
        Decision decision = new Decision("safe-recovery", DecisionOutcome.DENY, "not a recovery operation",
                new PolicyRef("oa.safe-recovery", "1"), NOW);

        new PolicyDecisionAudit(trail, CLOCK).record(new PolicyDecisionRecord(request, decision, true));

        assertEquals(1, trail.events.size());
        AuditEvent event = trail.events.get(0);
        assertEquals(PolicyDecisionAudit.ACTION, event.action());
        assertEquals(PolicyDecisionAudit.RESOURCE_TYPE, event.resourceType());
        assertEquals("operation:retry", event.resourceId());
        assertEquals(operator, event.actor());
        assertEquals("DENY", event.attributes().get("oa.outcome"));
        assertEquals("safe-recovery", event.attributes().get("oa.decided-by"));
        assertEquals("true", event.attributes().get("oa.recovery-path"));
        assertEquals("oa.safe-recovery", event.attributes().get("stamp.policy"));
        assertEquals("1", event.attributes().get("stamp.policy-set"));
        assertEquals(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS), event.occurredAt());
    }

    @Test
    void aFailedAppendReachesThePepAsAnException() {
        CapturingTrail trail = new CapturingTrail();
        trail.failure = new AuditStoreException("trail down", null);
        PolicyDecisionAudit bridge = new PolicyDecisionAudit(trail, CLOCK);
        AuthorizationRequest request = new AuthorizationRequest(new Subject(SubjectKind.USER, "ops-lead"),
                ActionKind.OPERATION, "operation:cancel", null, new PolicyContext(Set.of(), null, null, null, null,
                null));
        Decision decision = new Decision("pep", DecisionOutcome.ERROR, "x", PolicyRef.FAIL_CLOSED, NOW);

        assertThrows(AuditStoreException.class,
                () -> bridge.record(new PolicyDecisionRecord(request, decision, false)));
        assertNull(trail.events.stream().findFirst().orElse(null));
    }
}
