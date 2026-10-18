package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;

/**
 * A toy remote agent for the spike. Input text decides the path:
 * "need-info" pauses in TASK_STATE_INPUT_REQUIRED until a follow-up message arrives,
 * "slow" stays WORKING until cancelled, anything else completes at once.
 */
@ApplicationScoped
public class CaseAgentExecutor implements AgentExecutor {

    /** Tasks currently running a "slow" path; cancel() flips the flag so the loop can end. */
    static final Map<String, Boolean> CANCEL_REQUESTED = new ConcurrentHashMap<>();
    /** Metadata seen on the last incoming message, for the cycle-control characterization (Q07). */
    static final Map<String, Map<String, Object>> LAST_METADATA = new ConcurrentHashMap<>();
    /** Username the server saw on the last request (Q06), taken from ServerCallContext. */
    static volatile String lastUser;

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        String taskId = context.getTaskId();
        String input = context.getUserInput();
        lastUser = context.getCallContext() == null || context.getCallContext().getUser() == null
                ? null : context.getCallContext().getUser().getUsername();
        LAST_METADATA.put(taskId, context.getMessage() == null || context.getMessage().metadata() == null
                ? Map.of() : Map.copyOf(context.getMessage().metadata()));

        if (context.getTask() == null) {
            emitter.submit();
        }
        if (input.contains("need-info") && (context.getTask() == null
                || context.getTask().status().state() == org.a2aproject.sdk.spec.TaskState.TASK_STATE_SUBMITTED)) {
            emitter.startWork();
            emitter.requiresInput(emitter.newAgentMessage(
                    java.util.List.of(new org.a2aproject.sdk.spec.TextPart("Which case id?")), Map.of()));
            return;
        }
        emitter.startWork();
        if (input.contains("slow")) {
            CANCEL_REQUESTED.put(taskId, false);
            while (!CANCEL_REQUESTED.getOrDefault(taskId, false)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            return;
        }
        emitter.complete(emitter.newAgentMessage(
                java.util.List.of(new org.a2aproject.sdk.spec.TextPart("done: " + input)), Map.of()));
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        CANCEL_REQUESTED.put(context.getTaskId(), true);
        emitter.cancel();
    }
}
