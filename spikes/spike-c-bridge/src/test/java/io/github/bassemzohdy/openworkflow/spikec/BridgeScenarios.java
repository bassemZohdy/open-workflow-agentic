package io.github.bassemzohdy.openworkflow.spikec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The scenarios shared by the three bridges. Each subclass gives the bridge letter and the bridge-specific facts
 * (which task is in flight at a kill, whether a header travels). A scenario starts one or two application
 * processes, kills them with SIGKILL and checks the database and the engine's lifecycle record of each process.
 */
abstract class BridgeScenarios {

    static final Duration WAIT = Duration.ofSeconds(60);
    static final String DEADLINE = "2026-10-19T00:00:00Z";

    /** "a", "b" or "c". */
    protected abstract String bridge();

    /** Whether the X-Correlation-Id header reaches the agent (HTTP bridges only). */
    protected abstract boolean headerExpected();

    /** Task the engine re-runs after a kill during the effect (the in-flight task of the workflow). */
    protected abstract String taskAfterKillMidEffect();

    /** Task the engine re-runs after a kill during the draft. */
    protected abstract String taskAfterKillMidDraft();

    /** Bridge C only: the agent run is restarted by our reconciler, not by a workflow task. */
    protected boolean reconcilerRestartsAgent() {
        return false;
    }

    @BeforeEach
    void freshDatabase() throws Exception {
        Harness.stopAll();
        Harness.resetDatabase();
    }

    @AfterEach
    void stopProcesses() {
        Harness.stopAll();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    protected String startInstance(String correlationId) throws Exception {
        Map<String, Object> body = Map.of("tenant", "t1", "user", "u1", "correlationId", correlationId,
                "deadline", DEADLINE, "budget", 5, "topic", "research " + bridge() + " " + correlationId);
        return (String) Harness.post("/spikec/instances/" + bridge(), body).get("instanceId");
    }

    protected Map<String, Object> query(String id) {
        Map<String, Object> q = Harness.get("/spikec/instances/" + id);
        assertNotNull(q, "instance " + id + " is not in the registry");
        return q;
    }

    protected String engineStatus(String id) {
        return (String) query(id).get("engineStatus");
    }

    protected String runStatus(String id) {
        Map<?, ?> run = (Map<?, ?>) query(id).get("run");
        return run == null ? null : (String) run.get("status");
    }

    protected String threadOf(String id) {
        return (String) query(id).get("thread_id");
    }

    @SuppressWarnings("unchecked")
    protected List<String> lifecycle(String id) {
        return (List<String>) query(id).get("lifecycle");
    }

    protected long effects(String id) {
        return Harness.count("select count(*) from spikec.external_sink where thread_id = ?", threadOf(id));
    }

    protected long publishSteps(String id) {
        return Harness.count("select count(*) from spikec.agent_step where thread_id = ? and node = 'publish'",
                threadOf(id));
    }

    protected long draftSteps(String id) {
        return Harness.count("select count(*) from spikec.agent_step where thread_id = ? and node = 'draft'",
                threadOf(id));
    }

    protected void approve(String id) throws Exception {
        Harness.post("/spikec/approvals", Map.of("instanceId", id));
    }

    /**
     * The instance is listening for the approval: the agent is at its interrupt, the engine is waiting, and the
     * approval listen task has started. An approval sent before the listen is not buffered (WP-003 Q02), so tests
     * never send one earlier.
     */
    protected void awaitWaitingForApproval(String id) throws Exception {
        Harness.await("agent at approval and engine waiting for " + id, WAIT,
                () -> "AWAITING_APPROVAL".equals(runStatus(id)) && "WAITING".equals(engineStatus(id))
                        && hasTask(lifecycle(id), "TaskStarted", "awaitApproval"));
    }

    protected void awaitCompleted(String id) throws Exception {
        Harness.await("workflow completed for " + id, WAIT,
                () -> lifecycle(id).contains("WorkflowCompleted"));
    }

    protected static boolean hasTask(List<String> lifecycle, String event, String taskName) {
        return lifecycle.stream().anyMatch(e -> e.startsWith(event + " ") && e.endsWith("/" + taskName));
    }

    // ---- scenarios --------------------------------------------------------------------------------------------

    /** What arrives at the agent with the call, and what the agent records about its own execution. */
    protected void contextArrives() throws Exception {
        Harness.App app = Harness.start("ctx-" + bridge(), Map.of()).awaitReady();
        String id = startInstance("c-ctx-" + bridge());
        awaitWaitingForApproval(id);

        Map<String, Object> run = (Map<String, Object>) query(id).get("run");
        Map<String, Object> received = Harness.JSON.readValue((String) run.get("received_ctx"),
                new TypeReference<>() {
                });
        assertEquals("t1", received.get("tenant"));
        assertEquals("u1", received.get("user"));
        assertEquals(id, received.get("instanceId"));
        assertEquals("c-ctx-" + bridge(), received.get("correlationId"));
        assertEquals(DEADLINE, received.get("deadline"));
        assertEquals(5, received.get("budget"));
        if (headerExpected()) {
            assertEquals("c-ctx-" + bridge(), run.get("received_header_correlation"));
        } else {
            assertNull(run.get("received_header_correlation"), "no HTTP hop, so no header");
        }
        // MDC is not carried by any bridge (WP-003 Q10). The agent node reads MDC and finds nothing.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) query(id).get("steps");
        assertTrue(steps.stream().allMatch(s -> s.get("mdc_correlation") == null), "MDC arrived: " + steps);
        assertEquals("c-ctx-" + bridge(), steps.get(0).get("correlation_in_state"));

        approve(id);
        awaitCompleted(id);
        assertEquals(1, effects(id));
        app.stop();
    }

    /** kill -9 after the effect is written and before the publish node returns. */
    protected void killMidEffect() throws Exception {
        Harness.App first = Harness.start("effect-1-" + bridge(), Map.of("spikec.publish.delay-ms", "600000"))
                .awaitReady();
        String id = startInstance("c-effect-" + bridge());
        awaitWaitingForApproval(id);
        approve(id);
        Harness.await("effect written", WAIT, () -> effects(id) >= 1);
        first.kill9();

        Harness.App second = Harness.start("effect-2-" + bridge(), Map.of()).awaitReady();
        awaitCompleted(id);

        assertEquals(1, effects(id), "the idempotency key keeps one row for the effect");
        assertTrue(publishSteps(id) >= 2, "the publish node ran again after the kill (at-least-once)");
        assertTrue(hasTask(lifecycle(id), "TaskStarted", taskAfterKillMidEffect()),
                "restarted instance re-ran " + taskAfterKillMidEffect() + ": " + lifecycle(id));
        if (reconcilerRestartsAgent()) {
            assertTrue(Harness.count("select count(*) from spikec.agent_step where thread_id = ? "
                    + "and node like 'reconciler-restart:%'", threadOf(id)) >= 1,
                    "reconciler restarted the agent run (recorded in the step log)");
        }
        second.stop();
    }

    /** kill -9 while the model call of the draft is in flight. */
    protected void killMidDraft() throws Exception {
        Harness.App first = Harness.start("draft-1-" + bridge(), Map.of("spikec.draft.delay-ms", "600000"))
                .awaitReady();
        String id = startInstance("c-draft-" + bridge());
        Harness.await("draft started", WAIT, () -> draftSteps(id) >= 1);
        first.kill9();

        Harness.App second = Harness.start("draft-2-" + bridge(), Map.of()).awaitReady();
        awaitWaitingForApproval(id);
        approve(id);
        awaitCompleted(id);

        assertEquals(1, effects(id));
        assertTrue(draftSteps(id) >= 2, "the draft node ran again (model call repeated)");
        assertTrue(hasTask(lifecycle(id), "TaskStarted", taskAfterKillMidDraft()) || second.logText()
                .contains("restarting agent run"), "restart of the draft path");
        second.stop();
    }

    /** Control for the idempotency key: with a random key per write, the restart writes the effect a second time. */
    protected void naiveEffectIsDuplicated() throws Exception {
        Map<String, String> naive = Map.of("spikec.effect.keyed", "false", "spikec.publish.delay-ms", "600000");
        Harness.App first = Harness.start("naive-1-" + bridge(), naive).awaitReady();
        String id = startInstance("c-naive-" + bridge());
        awaitWaitingForApproval(id);
        approve(id);
        Harness.await("effect written", WAIT, () -> effects(id) >= 1);
        first.kill9();

        Harness.start("naive-2-" + bridge(), Map.of("spikec.effect.keyed", "false")).awaitReady();
        awaitCompleted(id);
        assertEquals(2, effects(id), "without the key the restart writes the effect again");
    }

    /** Waiting for approval, the process dies, comes back, and the approval still resumes the run exactly once. */
    protected void killWhileWaiting() throws Exception {
        Harness.App first = Harness.start("wait-1-" + bridge(), Map.of()).awaitReady();
        String id = startInstance("c-wait-" + bridge());
        awaitWaitingForApproval(id);
        first.kill9();

        Harness.App second = Harness.start("wait-2-" + bridge(), Map.of()).awaitReady();
        Harness.await("restored instance listening again", WAIT, () -> "WAITING".equals(engineStatus(id)));
        assertEquals(0, effects(id), "nothing ran while waiting");
        approve(id);
        awaitCompleted(id);

        assertEquals(1, effects(id));
        assertTrue(hasTask(lifecycle(id), "TaskStarted", "awaitApproval"), "the listen was registered after restart");
        second.stop();
    }

    /** Two instances wait on the same event type. An approval for one must not wake the other. */
    protected void approvalWakesOnlyItsInstance() throws Exception {
        Harness.start("two-" + bridge(), Map.of()).awaitReady();
        String first = startInstance("c-two-a-" + bridge());
        String second = startInstance("c-two-b-" + bridge());
        awaitWaitingForApproval(first);
        awaitWaitingForApproval(second);

        approve(first);
        awaitCompleted(first);
        assertEquals(1, effects(first));
        assertEquals(0, effects(second));
        assertEquals("WAITING", engineStatus(second));
        assertEquals("AWAITING_APPROVAL", runStatus(second));
    }
}
