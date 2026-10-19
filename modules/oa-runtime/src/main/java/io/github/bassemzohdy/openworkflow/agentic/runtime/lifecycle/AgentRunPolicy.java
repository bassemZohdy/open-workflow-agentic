package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/**
 * Decides what the reconciler does with an agent run that is recorded as RUNNING or RESUMING but has no live lease
 * (ADR 0023, decision 7; WP-105c scope item 2(v)). A pure rule, so the rule itself is tested without a database.
 *
 * <p><b>The rule.</b>
 * <ul>
 *   <li>A bridge A call (HTTP to an endpoint of the same deployable) or a bridge B call (in-process function) is not
 *       restarted by the reconciler. The workflow re-runs the in-flight call; the agent resumes from its checkpoint.
 *       The reconciler would otherwise run the same step twice.</li>
 *   <li>An asynchronous bridge C run is restarted by the reconciler, because no workflow task is re-running it. In
 *       105c no restarter is registered (the agent runtime is WP-201), so the decision is recorded and nothing starts.</li>
 *   <li>A run with an unknown bridge (null, or a value not listed here) is reported and nothing is restarted.
 *       Failing closed is the default.</li>
 *   <li>A run whose lease is live belongs to a running process. Nothing is done.</li>
 * </ul>
 */
public final class AgentRunPolicy {

    /** What the reconciler does with one run. */
    public enum Decision {
        /** A live process holds the run. */
        LIVE,
        /** The workflow re-runs the call (bridge A or B). The reconciler records it and nothing else. */
        LEFT_TO_WORKFLOW,
        /** Bridge C: the reconciler should restart the run. No restarter is registered in 105c. */
        RESTART_BY_RECONCILER,
        /** Bridge unknown or not recognised: report only. */
        REPORT_UNKNOWN_BRIDGE
    }

    private AgentRunPolicy() {
    }

    /**
     * @param bridge    the stored bridge kind, or null
     * @param leaseLive true when the run's lease has not expired
     */
    public static Decision decide(String bridge, boolean leaseLive) {
        if (leaseLive) {
            return Decision.LIVE;
        }
        if (bridge == null) {
            return Decision.REPORT_UNKNOWN_BRIDGE;
        }
        return switch (bridge) {
            case "HTTP_SAME_APP", "IN_PROCESS" -> Decision.LEFT_TO_WORKFLOW;
            case "ASYNC_CALLBACK" -> Decision.RESTART_BY_RECONCILER;
            default -> Decision.REPORT_UNKNOWN_BRIDGE;
        };
    }
}
