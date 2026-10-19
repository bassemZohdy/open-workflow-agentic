package io.github.bassemzohdy.openworkflow.spikec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bridge A: the workflow calls the agent over HTTP in the same application ({@code call: http} to
 * {@code /agents/research/run} and {@code /resume}). Definition: flow/bridge-a.yaml.
 */
class BridgeAIT extends BridgeScenarios {

    @Override
    protected String bridge() {
        return "a";
    }

    @Override
    protected boolean headerExpected() {
        return true;
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
    void BridgeA_Q1_contextArrivesInAgent() throws Exception {
        contextArrives();
    }

    @Test
    void BridgeA_Q2_agentKilledMidStep() throws Exception {
        killMidEffect();
    }

    @Test
    void BridgeA_Q2_agentKilledMidDraft() throws Exception {
        killMidDraft();
    }

    @Test
    void BridgeA_Q2_killWhileWaitingForApproval() throws Exception {
        killWhileWaiting();
    }

    /** Control for the restore gap (WP-003 fact 1): without our reconciler a restored instance never listens again. */
    @Test
    void BridgeA_Q2_withoutReconcilerRestoredInstanceStaysIdle() throws Exception {
        Harness.App first = Harness.start("norec-1", Map.of()).awaitReady();
        String id = startInstance("c-norec-a");
        awaitWaitingForApproval(id);
        first.kill9();

        Harness.App second = Harness.start("norec-2", Map.of("spikec.reconcile.enabled", "false")).awaitReady();
        approve(id);
        Thread.sleep(8_000);

        assertEquals(0, effects(id), "the approval reached no listener");
        assertFalse(hasTask(lifecycle(id), "TaskStarted", "awaitApproval"), "no listen was registered: " + lifecycle(id));
        second.stop();
    }

    /** Control for the idempotency key: without it the same effect is written twice after the kill. */
    @Test
    void BridgeA_Q3_naiveEffectWithoutKeyIsDuplicated() throws Exception {
        naiveEffectIsDuplicated();
    }

    @Test
    void BridgeA_Q7_approvalWakesOnlyItsInstance() throws Exception {
        approvalWakesOnlyItsInstance();
    }

}
