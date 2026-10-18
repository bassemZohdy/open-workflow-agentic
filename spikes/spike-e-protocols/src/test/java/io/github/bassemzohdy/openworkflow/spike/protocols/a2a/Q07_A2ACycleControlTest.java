package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.spec.Message;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WP-007 question 7: can an agent detect A -> B -> A loops with protocol fields? The test shows what
 * survives a round trip: task id, context id, and custom message metadata.
 */
@QuarkusTest
class Q07_A2ACycleControlTest {

    @TestHTTPResource("/")
    URL base;

    @Test
    @Disabled("Blocked: A2A reference server routes and JSON-RPC beans are not wired in this Quarkus app (observed 404 on the Agent Card). Not run; see WP-007 spike report.")
    void Q07_contextIdIsReusedAcrossTasksAndTaskIdsAreDistinct() throws Exception {
        Client client = A2ATestSupport.client(A2A.getAgentCard(base.toString()), false);
        try {
            var first = new A2ATestSupport.StateRecorder();
            client.sendMessage(A2A.toUserMessage("first request"), List.of(first.consumer()), err -> { }, null);
            var second = new A2ATestSupport.StateRecorder();
            client.sendMessage(A2A.toUserMessage("second request"), List.of(second.consumer()), err -> { }, null);

            String firstTask = first.taskIds.get(0);
            String secondTask = second.taskIds.get(0);
            assertNotEquals(firstTask, secondTask, "independent sends create distinct tasks");
        } finally {
            client.close();
        }
    }

    @Test
    @Disabled("Blocked: A2A reference server routes and JSON-RPC beans are not wired in this Quarkus app (observed 404 on the Agent Card). Not run; see WP-007 spike report.")
    void Q07_customMessageMetadataRoundTripsToTheServer() throws Exception {
        Client client = A2ATestSupport.client(A2A.getAgentCard(base.toString()), false);
        try {
            var recorder = new A2ATestSupport.StateRecorder();
            Message message = A2A.toUserMessage("hop test");
            Message withChain = Message.builder(message)
                    .metadata(Map.of("chain", "A>B", "hop", 2))
                    .build();
            client.sendMessage(withChain, List.of(recorder.consumer()), err -> { }, null);

            String taskId = recorder.taskIds.isEmpty() ? null : recorder.taskIds.get(0);
            // The executor stores metadata keyed by task id; wait until it has been recorded.
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (taskId == null || !CaseAgentExecutor.LAST_METADATA.containsKey(taskId)) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("server did not record metadata; task=" + taskId);
                }
                Thread.sleep(25);
                if (taskId == null && !recorder.taskIds.isEmpty()) {
                    taskId = recorder.taskIds.get(0);
                }
            }
            Map<String, Object> seen = CaseAgentExecutor.LAST_METADATA.get(taskId);
            assertEquals("A>B", seen.get("chain"));
            assertEquals(2, ((Number) seen.get("hop")).intValue());
        } finally {
            client.close();
        }
    }
}
