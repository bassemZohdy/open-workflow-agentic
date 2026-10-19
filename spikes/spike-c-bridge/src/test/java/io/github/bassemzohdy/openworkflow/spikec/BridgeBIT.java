package io.github.bassemzohdy.openworkflow.spikec;

import org.junit.jupiter.api.Test;

/**
 * Bridge B: the workflow's Java function calls the agent bean in the same JVM (flow definition:
 * {@link InProcessFlow}, no HTTP between them).
 */
class BridgeBIT extends BridgeScenarios {

    @Override
    protected String bridge() {
        return "b";
    }

    @Override
    protected boolean headerExpected() {
        return false;
    }

    @Override
    protected String taskAfterKillMidEffect() {
        return "resumeAgent";
    }

    @Override
    protected String taskAfterKillMidDraft() {
        return "runAgent";
    }

    @Test
    void BridgeB_Q1_contextArrivesInProcess() throws Exception {
        contextArrives();
    }

    @Test
    void BridgeB_Q2_agentKilledMidStep() throws Exception {
        killMidEffect();
    }

    @Test
    void BridgeB_Q2_agentKilledMidDraft() throws Exception {
        killMidDraft();
    }

    @Test
    void BridgeB_Q2_killWhileWaitingForApproval() throws Exception {
        killWhileWaiting();
    }

    @Test
    void BridgeB_Q7_approvalWakesOnlyItsInstance() throws Exception {
        approvalWakesOnlyItsInstance();
    }

    @Test
    void BridgeB_Q3_naiveEffectWithoutKeyIsDuplicated() throws Exception {
        naiveEffectIsDuplicated();
    }
}
