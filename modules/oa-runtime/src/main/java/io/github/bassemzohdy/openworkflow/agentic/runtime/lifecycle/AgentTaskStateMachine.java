package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The allowed status changes of an agent task (ADR 0002, ADR 0020, ADR 0023). Anything not listed is illegal.
 *
 * <pre>
 * PENDING           -> RUNNING, FAILED, CANCELLED
 * RUNNING           -> WAITING_FOR_HUMAN, COMPLETED, FAILED, CANCELLED
 * WAITING_FOR_HUMAN -> RESUMING, FAILED, CANCELLED
 * RESUMING          -> RUNNING, WAITING_FOR_HUMAN, FAILED, CANCELLED
 * COMPLETED, FAILED, CANCELLED -> (none: terminal)
 * </pre>
 *
 * RESUMING exists so that the reconciler can tell a run that was interrupted at a human decision and is being
 * resumed from a run that is simply executing (ADR 0023, decision 7). WAITING_FOR_HUMAN is entered only from a
 * running step, because an interrupt happens inside a step.
 */
public final class AgentTaskStateMachine {

    private static final Map<AgentTaskStatus, Set<AgentTaskStatus>> ALLOWED =
            new EnumMap<>(AgentTaskStatus.class);

    static {
        ALLOWED.put(AgentTaskStatus.PENDING,
                EnumSet.of(AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED));
        ALLOWED.put(AgentTaskStatus.RUNNING,
                EnumSet.of(AgentTaskStatus.WAITING_FOR_HUMAN, AgentTaskStatus.COMPLETED, AgentTaskStatus.FAILED,
                        AgentTaskStatus.CANCELLED));
        ALLOWED.put(AgentTaskStatus.WAITING_FOR_HUMAN,
                EnumSet.of(AgentTaskStatus.RESUMING, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED));
        ALLOWED.put(AgentTaskStatus.RESUMING,
                EnumSet.of(AgentTaskStatus.RUNNING, AgentTaskStatus.WAITING_FOR_HUMAN, AgentTaskStatus.FAILED,
                        AgentTaskStatus.CANCELLED));
        ALLOWED.put(AgentTaskStatus.COMPLETED, EnumSet.noneOf(AgentTaskStatus.class));
        ALLOWED.put(AgentTaskStatus.FAILED, EnumSet.noneOf(AgentTaskStatus.class));
        ALLOWED.put(AgentTaskStatus.CANCELLED, EnumSet.noneOf(AgentTaskStatus.class));
    }

    private AgentTaskStateMachine() {
    }

    /** The status a new task row must have. */
    public static AgentTaskStatus initial() {
        return AgentTaskStatus.PENDING;
    }

    /** True when the status may change from {@code from} to {@code to}. Equal states are not a transition: false. */
    public static boolean canMove(AgentTaskStatus from, AgentTaskStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** The statuses that {@code from} may move to. Empty for terminal states. */
    public static Set<AgentTaskStatus> allowedFrom(AgentTaskStatus from) {
        return EnumSet.copyOf(ALLOWED.get(from));
    }

    /**
     * Checks a status change.
     *
     * @throws IllegalTransitionException if the move is not allowed
     */
    public static void requireMove(AgentTaskStatus from, AgentTaskStatus to) {
        if (!canMove(from, to)) {
            throw new IllegalTransitionException("agent task", from, to);
        }
    }
}
