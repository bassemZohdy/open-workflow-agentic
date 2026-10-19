package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyContextTest {

    private static final Ulid TASK = Samples.TASK_ID;

    @Test
    void emptyContextHasNoFacts() {
        PolicyContext empty = PolicyContext.EMPTY;
        assertTrue(empty.scopes().isEmpty());
        assertNull(empty.riskTier());
        assertNull(empty.budgetRemainingMicros());
        assertNull(empty.onBehalfOf());
        assertNull(empty.delegationGrantId());
        assertNull(empty.permittedByContract());
    }

    @Test
    void scopesAreImmutableCopies() {
        Set<String> scopes = new HashSet<>(Set.of("cases:read"));
        PolicyContext context = new PolicyContext(scopes, RiskTier.LOW, 10L, null, null, true);
        scopes.add("cases:write");
        assertEquals(Set.of("cases:read"), context.scopes());
        assertThrows(UnsupportedOperationException.class, () -> context.scopes().add("x"));
    }

    @Test
    void scopesMustBeTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyContext(Set.of("Cases:Read"), null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyContext(Set.of("has space"), null, null, null, null, null));
    }

    @Test
    void budgetIsBoundedWhenPresent() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyContext(Set.of(), null, -1L, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyContext(Set.of(), null, ExecutionLimits.MAX_COST_MICROS + 1, null, null, null));
        PolicyContext zero = new PolicyContext(Set.of(), null, 0L, null, null, null);
        assertEquals(0L, zero.budgetRemainingMicros());
    }

    @Test
    void requestWithoutContextUsesEmpty() {
        Subject agent = new Subject(SubjectKind.AGENT, "research-agent");
        AuthorizationRequest request = new AuthorizationRequest(agent, ActionKind.TOOL_CALL, "tool:search", TASK);
        assertEquals(PolicyContext.EMPTY, request.context());
    }

    @Test
    void requestRejectsNullContext() {
        Subject agent = new Subject(SubjectKind.AGENT, "research-agent");
        assertThrows(NullPointerException.class,
                () -> new AuthorizationRequest(agent, ActionKind.TOOL_CALL, "tool:search", TASK, null));
    }

    @Test
    void actionKindsCoverTheFourBoundariesAndOperations() {
        Set<ActionKind> kinds = Set.of(ActionKind.values());
        assertTrue(kinds.containsAll(Set.of(ActionKind.TOOL_CALL, ActionKind.MODEL_CALL,
                ActionKind.HUMAN_TASK_ACTION, ActionKind.PUBLICATION, ActionKind.OPERATION)));
    }
}
