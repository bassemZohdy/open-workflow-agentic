package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WP-007 question 5: an SDK client and the reference server, in one test JVM. Sends tasks, observes
 * state transitions (including input-required), and cancels a running task.
 */
@QuarkusTest
class Q05_A2ATaskLifecycleTest {

    @TestHTTPResource("/")
    URL base;

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(25);
        }
    }

    @Test
    @Disabled("Blocked: A2A reference server routes and JSON-RPC beans are not wired in this Quarkus app (observed 404 on the Agent Card). Not run; see WP-007 spike report.")
    void Q05_completedTaskStreamsWorkingThenCompleted() throws Exception {
        Client client = A2ATestSupport.client(A2A.getAgentCard(base.toString()), true);
        try {
            var recorder = new A2ATestSupport.StateRecorder();
            Message message = A2A.toUserMessage("check case C-100");
            client.sendMessage(message, List.of(recorder.consumer()), err -> { }, null);

            await(() -> recorder.hasState(TaskState.TASK_STATE_COMPLETED));
            assertTrue(recorder.hasState(TaskState.TASK_STATE_WORKING), "states: " + recorder.states);
            assertEquals(TaskState.TASK_STATE_COMPLETED, recorder.states.get(recorder.states.size() - 1));
        } finally {
            client.close();
        }
    }

    @Test
    @Disabled("Blocked: A2A reference server routes and JSON-RPC beans are not wired in this Quarkus app (observed 404 on the Agent Card). Not run; see WP-007 spike report.")
    void Q05_inputRequiredPausesTaskAndFollowUpResumesIt() throws Exception {
        Client client = A2ATestSupport.client(A2A.getAgentCard(base.toString()), true);
        try {
            var first = new A2ATestSupport.StateRecorder();
            client.sendMessage(A2A.toUserMessage("need-info about the case"), List.of(first.consumer()),
                    err -> { }, null);
            await(() -> first.hasState(TaskState.TASK_STATE_INPUT_REQUIRED));
            String taskId = first.taskIds.get(0);
            String contextId = first.contextIds.get(0);

            // A follow-up on the same task resumes it. The reference server reuses the task id.
            var second = new A2ATestSupport.StateRecorder();
            Message followUp = Message.builder()
                    .role(Message.Role.ROLE_USER)
                    .parts(new org.a2aproject.sdk.spec.TextPart("C-101"))
                    .contextId(contextId)
                    .taskId(taskId)
                    .build();
            client.sendMessage(followUp, List.of(second.consumer()), err -> { }, null);
            await(() -> second.hasState(TaskState.TASK_STATE_COMPLETED));
            assertTrue(second.taskIds.contains(taskId), "follow-up stays on the same task: " + second.taskIds);
        } finally {
            client.close();
        }
    }

    @Test
    @Disabled("Blocked: A2A reference server routes and JSON-RPC beans are not wired in this Quarkus app (observed 404 on the Agent Card). Not run; see WP-007 spike report.")
    void Q05_runningTaskCanBeCanceled() throws Exception {
        Client client = A2ATestSupport.client(A2A.getAgentCard(base.toString()), true);
        try {
            var recorder = new A2ATestSupport.StateRecorder();
            client.sendMessage(A2A.toUserMessage("slow lookup of C-102"), List.of(recorder.consumer()),
                    err -> { }, null);
            await(() -> recorder.hasState(TaskState.TASK_STATE_WORKING));
            String taskId = recorder.taskIds.get(0);

            Task canceled = client.cancelTask(new CancelTaskParams(taskId), null);
            assertEquals(TaskState.TASK_STATE_CANCELED, canceled.status().state());
        } finally {
            client.close();
        }
    }
}
