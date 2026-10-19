package io.github.bassemzohdy.openworkflow.spikec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bridge C: the task starts the agent run asynchronously (202) and the agent calls back with CloudEvents that wake
 * listen tasks. Definition: flow/bridge-c.yaml.
 */
class BridgeCIT extends BridgeScenarios {

    @Override
    protected String bridge() {
        return "c";
    }

    @Override
    protected boolean headerExpected() {
        return true;
    }

    @Override
    protected String taskAfterKillMidEffect() {
        return "awaitAgentCompleted";
    }

    @Override
    protected String taskAfterKillMidDraft() {
        return "awaitAgentStarted";
    }

    @Override
    protected boolean reconcilerRestartsAgent() {
        return true;
    }

    @Test
    void BridgeC_Q1_contextArrivesInAgent() throws Exception {
        contextArrives();
    }

    @Test
    void BridgeC_Q2_agentKilledMidStep() throws Exception {
        killMidEffect();
    }

    @Test
    void BridgeC_Q2_agentKilledMidDraft() throws Exception {
        killMidDraft();
    }

    @Test
    void BridgeC_Q2_killWhileWaitingForApproval() throws Exception {
        killWhileWaiting();
    }

    /**
     * Characterization of the plain callback (redelivery off, as in the first version of this spike): the agent sends
     * its start callback before the start task returns, so the listen is not registered yet and the event is lost.
     * The instance then waits at awaitAgentStarted for ever. The plain form lost the callback in 3 of 10 normal runs
     * (measured by hand against a running process, see the findings document).
     */
    @Test
    void BridgeC_Q5_plainCallbackBeforeListenIsLost() throws Exception {
        Harness.App app = Harness.start("early-c-plain", Map.of("spikec.c.early-callback", "true",
                "spikec.c.redeliver", "false")).awaitReady();
        String id = startInstance("c-early-plain");
        Thread.sleep(4_000);

        assertTrue(hasTask(lifecycle(id), "TaskStarted", "awaitAgentStarted"));
        assertFalse(hasTask(lifecycle(id), "TaskCompleted", "awaitAgentStarted"),
                "the callback was lost: " + lifecycle(id));
        assertEquals("WAITING", engineStatus(id));
        assertEquals("AWAITING_APPROVAL", runStatus(id), "the agent did reach its interrupt");
        app.stop();
    }

    /** Same forced order, with the outbox: the callback is redelivered until the listen consumes it. */
    @Test
    void BridgeC_Q5_outboxRecoversCallbackSentBeforeListen() throws Exception {
        Harness.start("early-c-outbox", Map.of("spikec.c.early-callback", "true")).awaitReady();
        String id = startInstance("c-early-outbox");
        awaitWaitingForApproval(id);
        assertTrue(hasTask(lifecycle(id), "TaskCompleted", "awaitAgentStarted"), "started callback consumed");
        approve(id);
        awaitCompleted(id);
        assertEquals(1, effects(id));
        assertEquals(0, Harness.count("select count(*) from spikec.agent_outbox where instance_id = ? and not acked",
                id), "every callback acknowledged");
    }

    @Test
    void BridgeC_Q7_approvalWakesOnlyItsInstance() throws Exception {
        approvalWakesOnlyItsInstance();
    }

    @Test
    void BridgeC_Q3_naiveEffectWithoutKeyIsDuplicated() throws Exception {
        naiveEffectIsDuplicated();
    }
}
