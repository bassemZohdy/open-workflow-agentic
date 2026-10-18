package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The allowed status changes of a registry instance (ADR 0019). Anything not listed is illegal.
 *
 * <pre>
 * STARTING         -> RUNNING, FAILED, CANCEL_REQUESTED
 * RUNNING          -> WAITING, COMPLETED, FAILED, CANCEL_REQUESTED
 * WAITING          -> RUNNING, COMPLETED, FAILED, CANCEL_REQUESTED
 * CANCEL_REQUESTED -> CANCELLED, COMPLETED, FAILED
 * CANCELLED, COMPLETED, FAILED -> (none: terminal)
 * </pre>
 *
 * A change that keeps the same status (a failed start attempt, an engine id recorded) is not a transition and is
 * not listed here; the store records it in history with from equal to to.
 */
public final class InstanceStateMachine {

    private static final Map<InstanceStatus, Set<InstanceStatus>> ALLOWED = new EnumMap<>(InstanceStatus.class);

    static {
        ALLOWED.put(InstanceStatus.STARTING,
                EnumSet.of(InstanceStatus.RUNNING, InstanceStatus.FAILED, InstanceStatus.CANCEL_REQUESTED));
        ALLOWED.put(InstanceStatus.RUNNING,
                EnumSet.of(InstanceStatus.WAITING, InstanceStatus.COMPLETED, InstanceStatus.FAILED,
                        InstanceStatus.CANCEL_REQUESTED));
        ALLOWED.put(InstanceStatus.WAITING,
                EnumSet.of(InstanceStatus.RUNNING, InstanceStatus.COMPLETED, InstanceStatus.FAILED,
                        InstanceStatus.CANCEL_REQUESTED));
        ALLOWED.put(InstanceStatus.CANCEL_REQUESTED,
                EnumSet.of(InstanceStatus.CANCELLED, InstanceStatus.COMPLETED, InstanceStatus.FAILED));
        ALLOWED.put(InstanceStatus.CANCELLED, EnumSet.noneOf(InstanceStatus.class));
        ALLOWED.put(InstanceStatus.COMPLETED, EnumSet.noneOf(InstanceStatus.class));
        ALLOWED.put(InstanceStatus.FAILED, EnumSet.noneOf(InstanceStatus.class));
    }

    private InstanceStateMachine() {
    }

    /** True when the status may change from {@code from} to {@code to}. Equal states are not a transition: false. */
    public static boolean canMove(InstanceStatus from, InstanceStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** The statuses that {@code from} may move to. Empty for terminal states. */
    public static Set<InstanceStatus> allowedFrom(InstanceStatus from) {
        return EnumSet.copyOf(ALLOWED.get(from));
    }

    /**
     * Checks a status change.
     *
     * @throws IllegalTransitionException if the move is not allowed
     */
    public static void requireMove(InstanceStatus from, InstanceStatus to) {
        if (!canMove(from, to)) {
            throw new IllegalTransitionException("instance", from, to);
        }
    }
}
