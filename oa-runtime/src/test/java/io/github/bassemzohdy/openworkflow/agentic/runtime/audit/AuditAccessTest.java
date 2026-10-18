package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import jakarta.ws.rs.ForbiddenException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditAccessTest {

    @Test
    void withoutAPolicyAndWithoutDevOpenTheRequestIsDenied() {
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(null, false).require(AuditAccess.READ));
    }

    @Test
    void devOpenAllowsOnlyWhenNoPolicyIsBound() {
        assertDoesNotThrow(() -> AuditAccess.of(null, true).require(AuditAccess.READ));
    }

    @Test
    void aBoundPolicyAllowsOnAllowAndDeniesOtherwise() {
        assertDoesNotThrow(() -> AuditAccess.of(policy(DecisionOutcome.ALLOW), false).require(AuditAccess.READ));
        assertThrows(ForbiddenException.class,
                () -> AuditAccess.of(policy(DecisionOutcome.DENY), false).require(AuditAccess.READ));
        assertThrows(ForbiddenException.class,
                () -> AuditAccess.of(policy(DecisionOutcome.ERROR), false).require(AuditAccess.READ));
    }

    @Test
    void aBoundPolicyOverridesDevOpen() {
        assertThrows(ForbiddenException.class,
                () -> AuditAccess.of(policy(DecisionOutcome.DENY), true).require(AuditAccess.READ));
    }

    @Test
    void aNullDecisionIsADenial() {
        PolicyDecisionPoint returnsNull = request -> null;
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(returnsNull, true).require(AuditAccess.VERIFY));
    }

    @Test
    void thePolicyIsAskedAboutTheAuditAction() {
        String[] asked = new String[1];
        PolicyDecisionPoint recording = request -> {
            asked[0] = request.resource();
            return decision(DecisionOutcome.ALLOW);
        };
        AuditAccess.of(recording, false).require(AuditAccess.VERIFY);
        assertEquals(AuditAccess.VERIFY, asked[0]);
    }

    private static PolicyDecisionPoint policy(DecisionOutcome outcome) {
        return request -> decision(outcome);
    }

    private static Decision decision(DecisionOutcome outcome) {
        return new Decision("test-pdp", outcome, "test decision", PolicyRef.FAIL_CLOSED, Instant.now());
    }
}
