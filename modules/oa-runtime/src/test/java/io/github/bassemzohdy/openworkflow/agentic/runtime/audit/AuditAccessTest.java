package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext.Provenance;
import jakarta.ws.rs.ForbiddenException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The access rule of the audit API with a caller (WP-102). There is no anonymous caller any more. */
class AuditAccessTest {

    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");

    private static CallerContext verified() {
        return new CallerContext("tenant-1", ALICE, Set.of("audit.read", "profile"), Provenance.VERIFIED_TOKEN);
    }

    private static CallerContext devHeaders() {
        return new CallerContext("tenant-1", ALICE, Set.of(), Provenance.DEV_HEADERS);
    }

    @Test
    void withoutAPolicyAndWithoutDevOpenTheRequestIsDeniedEvenForAVerifiedCaller() {
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(null, false).require(AuditAccess.READ, verified()));
    }

    @Test
    void devOpenAllowsAnAuthenticatedCallerOnlyWhenNoPolicyIsBound() {
        assertDoesNotThrow(() -> AuditAccess.of(null, true).require(AuditAccess.READ, verified()));
        assertDoesNotThrow(() -> AuditAccess.of(null, true).require(AuditAccess.VERIFY, devHeaders()));
    }

    @Test
    void aBoundPolicyAllowsOnAllowAndDeniesOtherwise() {
        assertDoesNotThrow(() -> AuditAccess.of(policy(DecisionOutcome.ALLOW), false)
                .require(AuditAccess.READ, verified()));
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(policy(DecisionOutcome.DENY), false)
                .require(AuditAccess.READ, verified()));
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(policy(DecisionOutcome.ERROR), false)
                .require(AuditAccess.READ, verified()));
    }

    @Test
    void aBoundPolicyOverridesDevOpen() {
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(policy(DecisionOutcome.DENY), true)
                .require(AuditAccess.READ, verified()));
    }

    @Test
    void aNullDecisionIsADenial() {
        PolicyDecisionPoint returnsNull = request -> null;
        assertThrows(ForbiddenException.class,
                () -> AuditAccess.of(returnsNull, true).require(AuditAccess.VERIFY, verified()));
    }

    @Test
    void aPolicyThatFailsIsNeverAnAllow() {
        PolicyDecisionPoint throwing = request -> {
            throw new IllegalStateException("engine down");
        };
        assertThrows(RuntimeException.class,
                () -> AuditAccess.of(throwing, true).require(AuditAccess.READ, verified()));
    }

    // ---- the caller is what the policy decides about --------------------------------------------------------------------

    @Test
    void thePolicyIsAskedAboutTheAuditActionForTheCallersOwnSubjectAndScopes() {
        List<AuthorizationRequest> asked = new ArrayList<>();
        PolicyDecisionPoint recording = request -> {
            asked.add(request);
            return decision(DecisionOutcome.ALLOW);
        };
        AuditAccess.of(recording, false).require(AuditAccess.VERIFY, verified());

        assertEquals(1, asked.size());
        AuthorizationRequest request = asked.get(0);
        assertEquals(AuditAccess.VERIFY, request.resource());
        assertEquals(ActionKind.DATA_ACCESS, request.action());
        assertEquals(ALICE, request.subject(), "the policy must see the verified caller, not a placeholder");
        assertEquals(Set.of("audit.read", "profile"), request.context().scopes());
        assertFalse("anonymous".equals(request.subject().id()));
    }

    @Test
    void anAgentCallerIsPassedAsAnAgentSoThePolicyCanTellItFromAUser() {
        Subject agent = new Subject(SubjectKind.AGENT, "agent-7");
        List<Subject> seen = new ArrayList<>();
        PolicyDecisionPoint recording = request -> {
            seen.add(request.subject());
            return decision(DecisionOutcome.DENY);
        };
        CallerContext caller = new CallerContext("tenant-1", agent, Set.of(), Provenance.VERIFIED_TOKEN);
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(recording, false).require(AuditAccess.READ, caller));
        assertEquals(List.of(agent), seen);
        assertEquals(SubjectKind.AGENT, seen.get(0).kind());
    }

    @Test
    void aCallerBuiltByHandIsRefusedAndThePolicyIsNotAsked() {
        boolean[] asked = {false};
        PolicyDecisionPoint allowAll = request -> {
            asked[0] = true;
            return decision(DecisionOutcome.ALLOW);
        };
        CallerContext handBuilt = new CallerContext("tenant-1", ALICE, Set.of("audit.read"));
        assertEquals(Provenance.UNVERIFIED, handBuilt.provenance());

        assertThrows(ForbiddenException.class, () -> AuditAccess.of(allowAll, false).require(AuditAccess.READ, handBuilt));
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(null, true).require(AuditAccess.READ, handBuilt),
                "dev-open removes the policy requirement, not the authentication requirement");
        assertFalse(asked[0], "an unverified caller must be refused before the policy is consulted");
    }

    @Test
    void aMissingCallerIsRefused() {
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(policy(DecisionOutcome.ALLOW), true)
                .require(AuditAccess.READ, null));
        assertThrows(ForbiddenException.class, () -> AuditAccess.of(null, true).require(AuditAccess.READ, null));
    }

    @Test
    void scopesThatCannotFormAPolicyContextAreADenialNotAServerError() {
        boolean[] asked = {false};
        PolicyDecisionPoint allowAll = request -> {
            asked[0] = true;
            return decision(DecisionOutcome.ALLOW);
        };
        CallerContext badSyntax = new CallerContext("tenant-1", ALICE, Set.of("Not A Token!"), Provenance.VERIFIED_TOKEN);
        Set<String> tooMany = IntStream.range(0, 101).mapToObj(i -> "scope." + i).collect(Collectors.toSet());
        CallerContext tooManyScopes = new CallerContext("tenant-1", ALICE, tooMany, Provenance.VERIFIED_TOKEN);

        assertThrows(ForbiddenException.class, () -> AuditAccess.of(allowAll, false).require(AuditAccess.READ, badSyntax));
        assertThrows(ForbiddenException.class,
                () -> AuditAccess.of(allowAll, false).require(AuditAccess.READ, tooManyScopes));
        assertFalse(asked[0]);
        assertTrue(tooMany.size() > 100);
    }

    private static PolicyDecisionPoint policy(DecisionOutcome outcome) {
        return request -> decision(outcome);
    }

    private static Decision decision(DecisionOutcome outcome) {
        return new Decision("test-pdp", outcome, "test decision", PolicyRef.FAIL_CLOSED, Instant.now());
    }
}
