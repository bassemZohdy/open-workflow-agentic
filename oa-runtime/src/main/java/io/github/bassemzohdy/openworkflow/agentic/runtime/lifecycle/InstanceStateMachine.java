package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The allowed status changes of a registry instance (ADR 0019). Anything not listed is illegal.
 *
 * <pre>
 * STARTING         -> RUNNING, FAILED, CANCEL_REQUESTED, CANCELLED (CANCELLED only with no engine instance id)
 * RUNNING          -> WAITING, COMPLETED, FAILED, CANCEL_REQUESTED
 * WAITING          -> RUNNING, COMPLETED, FAILED, CANCEL_REQUESTED
 * CANCEL_REQUESTED -> CANCELLED, COMPLETED, FAILED
 * CANCELLED, COMPLETED, FAILED -> (none: terminal)
 * </pre>
 *
 * A change that keeps the same status (a failed start attempt, an engine id recorded) is not a transition and is
 * not listed here; the store records it in history with from equal to to.
 *
 * <p>The edge STARTING to CANCELLED (ADR 0019, amendment 4, issue #24) is legal only while no engine instance id has
 * been recorded: such an instance was never started, so nothing needs the engine's report. The two-argument methods
 * check the status pair only; the store checks the engine id rule with the three-argument methods on every write.
 */
public final class InstanceStateMachine {

    private static final Map<InstanceStatus, Set<InstanceStatus>> ALLOWED = new EnumMap<>(InstanceStatus.class);

    static {
        ALLOWED.put(InstanceStatus.STARTING,
                EnumSet.of(InstanceStatus.RUNNING, InstanceStatus.FAILED, InstanceStatus.CANCEL_REQUESTED,
                        InstanceStatus.CANCELLED));
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

    /**
     * True when the status pair is an edge of the table. Equal states are not a transition: false. This does not apply
     * the engine id rule of STARTING to CANCELLED; use {@link #canMove(InstanceStatus, InstanceStatus, boolean)}.
     */
    public static boolean canMove(InstanceStatus from, InstanceStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /**
     * True when the change is allowed for a row whose engine instance id is recorded or not. Identical to
     * {@link #canMove(InstanceStatus, InstanceStatus)} except for STARTING to CANCELLED, which is refused once an
     * engine instance id has been recorded.
     *
     * @param engineInstanceRecorded true when the row already names an engine instance, or the change sets one
     */
    public static boolean canMove(InstanceStatus from, InstanceStatus to, boolean engineInstanceRecorded) {
        if (from == InstanceStatus.STARTING && to == InstanceStatus.CANCELLED) {
            return !engineInstanceRecorded;
        }
        return canMove(from, to);
    }

    /** The statuses that {@code from} may move to. Empty for terminal states. */
    public static Set<InstanceStatus> allowedFrom(InstanceStatus from) {
        return EnumSet.copyOf(ALLOWED.get(from));
    }

    /**
     * Checks a status change of the pair alone.
     *
     * @throws IllegalTransitionException if the move is not allowed
     */
    public static void requireMove(InstanceStatus from, InstanceStatus to) {
        if (!canMove(from, to)) {
            throw new IllegalTransitionException("instance", from, to);
        }
    }

    /**
     * Checks a status change with the engine id rule.
     *
     * @throws IllegalTransitionException if the move is not allowed for this row
     */
    public static void requireMove(InstanceStatus from, InstanceStatus to, boolean engineInstanceRecorded) {
        if (!canMove(from, to, engineInstanceRecorded)) {
            throw new IllegalTransitionException("instance", from, to);
        }
    }
}
