package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The transition tables are written out here as literal edge lists, separately from the implementation. Every
 * pair of states is checked, so a new state or a changed edge fails a test.
 */
class StateMachineTest {

    static final List<String[]> INSTANCE_EDGES = List.of(
            // from, to
            new String[] {"STARTING", "RUNNING"},
            new String[] {"STARTING", "FAILED"},
            new String[] {"STARTING", "CANCEL_REQUESTED"},
            new String[] {"RUNNING", "WAITING"},
            new String[] {"RUNNING", "COMPLETED"},
            new String[] {"RUNNING", "FAILED"},
            new String[] {"RUNNING", "CANCEL_REQUESTED"},
            new String[] {"WAITING", "RUNNING"},
            new String[] {"WAITING", "COMPLETED"},
            new String[] {"WAITING", "FAILED"},
            new String[] {"WAITING", "CANCEL_REQUESTED"},
            new String[] {"CANCEL_REQUESTED", "CANCELLED"},
            new String[] {"CANCEL_REQUESTED", "COMPLETED"},
            new String[] {"CANCEL_REQUESTED", "FAILED"});

    static final List<String[]> TASK_EDGES = List.of(
            new String[] {"PENDING", "RUNNING"},
            new String[] {"PENDING", "FAILED"},
            new String[] {"PENDING", "CANCELLED"},
            new String[] {"RUNNING", "WAITING_FOR_HUMAN"},
            new String[] {"RUNNING", "COMPLETED"},
            new String[] {"RUNNING", "FAILED"},
            new String[] {"RUNNING", "CANCELLED"},
            new String[] {"WAITING_FOR_HUMAN", "RESUMING"},
            new String[] {"WAITING_FOR_HUMAN", "FAILED"},
            new String[] {"WAITING_FOR_HUMAN", "CANCELLED"},
            new String[] {"RESUMING", "RUNNING"},
            new String[] {"RESUMING", "WAITING_FOR_HUMAN"},
            new String[] {"RESUMING", "FAILED"},
            new String[] {"RESUMING", "CANCELLED"});

    @Test
    void instanceTransitionsMatchTheTableForEveryPairOfStates() {
        Set<String> allowed = edges(INSTANCE_EDGES);
        for (InstanceStatus from : InstanceStatus.values()) {
            for (InstanceStatus to : InstanceStatus.values()) {
                boolean expected = allowed.contains(from + ">" + to);
                assertEquals(expected, InstanceStateMachine.canMove(from, to), from + " -> " + to);
                if (expected) {
                    InstanceStateMachine.requireMove(from, to);
                } else {
                    assertThrows(IllegalTransitionException.class,
                            () -> InstanceStateMachine.requireMove(from, to), from + " -> " + to);
                }
            }
        }
        assertEquals(INSTANCE_EDGES.size(), 14, "the table has 14 edges");
    }

    @Test
    void agentTaskTransitionsMatchTheTableForEveryPairOfStates() {
        Set<String> allowed = edges(TASK_EDGES);
        for (AgentTaskStatus from : AgentTaskStatus.values()) {
            for (AgentTaskStatus to : AgentTaskStatus.values()) {
                boolean expected = allowed.contains(from + ">" + to);
                assertEquals(expected, AgentTaskStateMachine.canMove(from, to), from + " -> " + to);
                if (expected) {
                    AgentTaskStateMachine.requireMove(from, to);
                } else {
                    assertThrows(IllegalTransitionException.class,
                            () -> AgentTaskStateMachine.requireMove(from, to), from + " -> " + to);
                }
            }
        }
        assertEquals(TASK_EDGES.size(), 14, "the table has 14 edges");
    }

    @ParameterizedTest
    @EnumSource(value = InstanceStatus.class, names = {"CANCELLED", "COMPLETED", "FAILED"})
    void terminalInstanceStatesHaveNoExit(InstanceStatus terminal) {
        assertTrue(terminal.isTerminal());
        assertTrue(InstanceStateMachine.allowedFrom(terminal).isEmpty());
        for (InstanceStatus to : InstanceStatus.values()) {
            assertFalse(InstanceStateMachine.canMove(terminal, to), terminal + " -> " + to);
        }
    }

    @ParameterizedTest
    @EnumSource(value = AgentTaskStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalAgentTaskStatesHaveNoExit(AgentTaskStatus terminal) {
        assertTrue(AgentTaskStateMachine.allowedFrom(terminal).isEmpty());
        for (AgentTaskStatus to : AgentTaskStatus.values()) {
            assertFalse(AgentTaskStateMachine.canMove(terminal, to), terminal + " -> " + to);
        }
    }

    @Test
    void onlyTheTerminalStatesAreTerminal() {
        Set<InstanceStatus> terminal = new HashSet<>();
        for (InstanceStatus s : InstanceStatus.values()) {
            if (s.isTerminal()) {
                terminal.add(s);
            }
        }
        assertEquals(Set.of(InstanceStatus.CANCELLED, InstanceStatus.COMPLETED, InstanceStatus.FAILED), terminal);
    }

    @Test
    void theSameStatusIsNotATransition() {
        for (InstanceStatus s : InstanceStatus.values()) {
            assertFalse(InstanceStateMachine.canMove(s, s), s.name());
        }
        for (AgentTaskStatus s : AgentTaskStatus.values()) {
            assertFalse(AgentTaskStateMachine.canMove(s, s), s.name());
        }
    }

    @Test
    void everyNonTerminalInstanceStateHasAnExit() {
        // Every state except the terminal ones has at least one exit, so no state is a dead end by mistake.
        for (InstanceStatus s : InstanceStatus.values()) {
            if (!s.isTerminal()) {
                assertFalse(InstanceStateMachine.allowedFrom(s).isEmpty(), s.name());
            }
        }
    }

    @Test
    void newAgentTasksStartPending() {
        assertEquals(AgentTaskStatus.PENDING, AgentTaskStateMachine.initial());
    }

    @Test
    void theIllegalTransitionMessageNamesBothStates() {
        IllegalTransitionException e = assertThrows(IllegalTransitionException.class,
                () -> InstanceStateMachine.requireMove(InstanceStatus.COMPLETED, InstanceStatus.RUNNING));
        assertTrue(e.getMessage().contains("COMPLETED") && e.getMessage().contains("RUNNING"), e.getMessage());
        assertTrue(e instanceof IllegalStateException);
    }

    private static Set<String> edges(List<String[]> pairs) {
        Set<String> out = new HashSet<>();
        for (String[] p : pairs) {
            out.add(p[0] + ">" + p[1]);
        }
        return out;
    }
}
