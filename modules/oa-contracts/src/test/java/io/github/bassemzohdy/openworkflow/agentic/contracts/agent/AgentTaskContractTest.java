package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.CHILD_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.GRANT_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.SCOPE;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.TOOL;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.USER;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.contract;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples.limits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.testdata.Samples;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentTaskContractTest {

    private static AgentTaskContract withTools(Set<String> tools, Set<String> scopes) {
        return new AgentTaskContract(TASK_ID, "goal", tools, scopes, RiskTier.MEDIUM, limits(), USER, GRANT_ID);
    }

    @Test
    void emptyAllowListsPermitNothing() {
        AgentTaskContract none = withTools(Set.of(), Set.of());
        assertFalse(none.permitsTool(TOOL));
        assertFalse(none.permitsDataScope(SCOPE));
    }

    @Test
    void onlyListedToolsAndScopesArePermitted() {
        AgentTaskContract c = contract();
        assertTrue(c.permitsTool(TOOL));
        assertTrue(c.permitsDataScope(SCOPE));
        assertFalse(c.permitsTool("tool:other"));
        assertFalse(c.permitsDataScope("case.write"));
    }

    @Test
    void nullBlankAndDifferentCaseNamesAreNotPermitted() {
        AgentTaskContract c = contract();
        assertFalse(c.permitsTool(null));
        assertFalse(c.permitsTool(""));
        assertFalse(c.permitsTool("TOOL:CASE-SEARCH"));
        assertFalse(c.permitsTool(TOOL + " "));
        assertFalse(c.permitsDataScope(null));
    }

    @Test
    void goalIsRequiredAndBounded() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskContract(TASK_ID, " ", Set.of(), Set.of(),
                RiskTier.LOW, limits(), USER, GRANT_ID));
        String tooLong = "g".repeat(AgentTaskContract.MAX_GOAL_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskContract(TASK_ID, tooLong, Set.of(),
                Set.of(), RiskTier.LOW, limits(), USER, GRANT_ID));
        assertThrows(NullPointerException.class, () -> new AgentTaskContract(TASK_ID, null, Set.of(), Set.of(),
                RiskTier.LOW, limits(), USER, GRANT_ID));
    }

    @Test
    void riskTierAndLimitsAreMandatory() {
        assertThrows(NullPointerException.class, () -> new AgentTaskContract(TASK_ID, "goal", Set.of(), Set.of(),
                null, limits(), USER, GRANT_ID));
        assertThrows(NullPointerException.class, () -> new AgentTaskContract(TASK_ID, "goal", Set.of(), Set.of(),
                RiskTier.LOW, null, USER, GRANT_ID));
        assertThrows(NullPointerException.class, () -> new AgentTaskContract(TASK_ID, "goal", Set.of(), Set.of(),
                RiskTier.LOW, limits(), null, GRANT_ID));
        assertThrows(NullPointerException.class, () -> new AgentTaskContract(TASK_ID, "goal", Set.of(), Set.of(),
                RiskTier.LOW, limits(), USER, null));
    }

    @Test
    void allowListsAreBoundedAndHaveAToolNameFormat() {
        Set<String> tooMany = new HashSet<>();
        for (int i = 0; i <= AgentTaskContract.MAX_ALLOW_LIST_SIZE; i++) {
            tooMany.add("tool." + i);
        }
        assertThrows(IllegalArgumentException.class, () -> withTools(tooMany, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> withTools(Set.of("Bad Tool"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> withTools(Set.of("1tool"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> withTools(Set.of(), Set.of("x".repeat(129))));
        assertThrows(NullPointerException.class, () -> withTools(null, Set.of()));
        assertThrows(NullPointerException.class, () -> withTools(Set.of(), Set.of((String) null)));
    }

    @Test
    void collectionsAreCopiedOnConstruction() {
        Set<String> source = new HashSet<>(Set.of(TOOL));
        AgentTaskContract c = withTools(source, Set.of());
        source.add("tool:late-addition");
        assertFalse(c.permitsTool("tool:late-addition"));
        assertThrows(UnsupportedOperationException.class, () -> c.permittedTools().add("tool:x"));
    }

    @Test
    void narrowKeepsGoalSubjectAndGrantAndReducesAuthority() {
        AgentTaskContract parent = contract();
        ExecutionLimits childLimits = new ExecutionLimits(5, Duration.ofMinutes(2), 1_000L);
        AgentTaskContract child = parent.narrow(CHILD_ID, Set.of(), Set.of(SCOPE), RiskTier.HIGH, childLimits);

        assertEquals(CHILD_ID, child.id());
        assertEquals(parent.goal(), child.goal());
        assertEquals(USER, child.requestingSubject());
        assertEquals(GRANT_ID, child.delegationGrantId());
        assertEquals(Set.of(), child.permittedTools());
        assertEquals(Set.of(SCOPE), child.permittedDataScopes());
        assertEquals(RiskTier.HIGH, child.riskTier());
        assertEquals(childLimits, child.limits());
        assertFalse(child.permitsTool(TOOL));
        assertTrue(parent.permitsTool(TOOL), "the parent is unchanged");
    }

    @Test
    void narrowToTheSameAuthorityIsAllowed() {
        AgentTaskContract parent = contract();
        AgentTaskContract child = parent.narrow(CHILD_ID, parent.permittedTools(), parent.permittedDataScopes(),
                parent.riskTier(), parent.limits());
        assertEquals(parent.permittedTools(), child.permittedTools());
    }

    @Test
    void narrowRejectsAToolTheParentDoesNotPermit() {
        AgentTaskContract parent = contract();
        PermissionWideningException e = assertThrows(PermissionWideningException.class,
                () -> parent.narrow(CHILD_ID, Set.of(TOOL, "tool:payments"), Set.of(), RiskTier.MEDIUM, limits()));
        assertTrue(e.getMessage().contains("tool:payments"));
    }

    @Test
    void narrowRejectsADataScopeTheParentDoesNotPermit() {
        AgentTaskContract parent = contract();
        assertThrows(PermissionWideningException.class,
                () -> parent.narrow(CHILD_ID, Set.of(), Set.of("case.write"), RiskTier.MEDIUM, limits()));
    }

    @Test
    void narrowRejectsALoweredRiskTier() {
        AgentTaskContract parent = contract();
        assertThrows(PermissionWideningException.class,
                () -> parent.narrow(CHILD_ID, Set.of(), Set.of(), RiskTier.LOW, limits()));
    }

    @Test
    void narrowRejectsLimitsAboveTheParent() {
        AgentTaskContract parent = contract();
        assertThrows(PermissionWideningException.class, () -> parent.narrow(CHILD_ID, Set.of(), Set.of(),
                RiskTier.MEDIUM, new ExecutionLimits(21, Duration.ofMinutes(1), 1L)));
        assertThrows(PermissionWideningException.class, () -> parent.narrow(CHILD_ID, Set.of(), Set.of(),
                RiskTier.MEDIUM, new ExecutionLimits(1, Duration.ofMinutes(11), 1L)));
        assertThrows(PermissionWideningException.class, () -> parent.narrow(CHILD_ID, Set.of(), Set.of(),
                RiskTier.MEDIUM, new ExecutionLimits(1, Duration.ofMinutes(1), 500_001L)));
    }

    @Test
    void narrowRejectsTheSameIdAndNeedsAnId() {
        AgentTaskContract parent = contract();
        assertThrows(IllegalArgumentException.class, () -> parent.narrow(TASK_ID, Set.of(), Set.of(),
                RiskTier.MEDIUM, limits()));
        assertThrows(NullPointerException.class, () -> parent.narrow(null, Set.of(), Set.of(), RiskTier.MEDIUM,
                limits()));
    }

    @Test
    void narrowingCannotBeUndoneByANarrowerChainStep() {
        AgentTaskContract parent = contract();
        AgentTaskContract child = parent.narrow(CHILD_ID, Set.of(), Set.of(SCOPE), RiskTier.MEDIUM, limits());
        // The grandchild may not regain the tool the child dropped.
        assertThrows(PermissionWideningException.class,
                () -> child.narrow(Samples.EVENT_ID, Set.of(TOOL), Set.of(), RiskTier.MEDIUM, limits()));
    }

    @Test
    void wideningIsAnIllegalArgument() {
        assertTrue(new PermissionWideningException("x") instanceof IllegalArgumentException);
    }

    @Test
    void riskTierOrdersLowMediumHigh() {
        assertTrue(RiskTier.HIGH.atLeast(RiskTier.MEDIUM));
        assertTrue(RiskTier.MEDIUM.atLeast(RiskTier.MEDIUM));
        assertFalse(RiskTier.LOW.atLeast(RiskTier.MEDIUM));
    }
}
