package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.AgentRunPolicy.Decision.LEFT_TO_WORKFLOW;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.AgentRunPolicy.Decision.LIVE;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.AgentRunPolicy.Decision.REPORT_UNKNOWN_BRIDGE;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.AgentRunPolicy.Decision.RESTART_BY_RECONCILER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** ADR 0023, decision 7: which agent runs the reconciler restarts (WP-105c, scope item 2(v)). */
class AgentRunPolicyTest {

    @Test
    void bridgeAAndBRunsAreLeftToTheWorkflowThatRerunsTheCall() {
        assertEquals(LEFT_TO_WORKFLOW, AgentRunPolicy.decide("HTTP_SAME_APP", false));
        assertEquals(LEFT_TO_WORKFLOW, AgentRunPolicy.decide("IN_PROCESS", false));
    }

    @Test
    void onlyAsynchronousBridgeCRunsAreRestartedByTheReconciler() {
        assertEquals(RESTART_BY_RECONCILER, AgentRunPolicy.decide("ASYNC_CALLBACK", false));
    }

    @Test
    void anUnknownBridgeIsReportedAndNothingIsRestarted() {
        assertEquals(REPORT_UNKNOWN_BRIDGE, AgentRunPolicy.decide(null, false));
        assertEquals(REPORT_UNKNOWN_BRIDGE, AgentRunPolicy.decide("SOMETHING_ELSE", false));
    }

    @Test
    void aRunWithALiveLeaseIsNeverTouched() {
        assertEquals(LIVE, AgentRunPolicy.decide("ASYNC_CALLBACK", true));
        assertEquals(LIVE, AgentRunPolicy.decide(null, true));
    }
}
