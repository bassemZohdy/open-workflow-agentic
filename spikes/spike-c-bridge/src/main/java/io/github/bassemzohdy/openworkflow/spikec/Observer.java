package io.github.bassemzohdy.openworkflow.spikec;

import io.quarkus.arc.Unremovable;
import io.serverlessworkflow.impl.lifecycle.TaskCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.TaskFailedEvent;
import io.serverlessworkflow.impl.lifecycle.TaskStartedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowCancelledEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowFailedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowStartedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the engine's lifecycle per instance (in memory), so the tests can see task failures and task
 * positions without parsing logs. Exposed on the instance query as {@code lifecycle}.
 */
@ApplicationScoped
@Unremovable
public class Observer implements WorkflowExecutionListener {

    private static final Map<String, List<String>> EVENTS = new ConcurrentHashMap<>();

    public static List<String> of(String instanceId) {
        return List.copyOf(EVENTS.getOrDefault(instanceId, List.of()));
    }

    private static void record(String instanceId, String what) {
        EVENTS.computeIfAbsent(instanceId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(what);
    }

    @Override
    public void onWorkflowStarted(WorkflowStartedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowStarted");
    }

    @Override
    public void onWorkflowCompleted(WorkflowCompletedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowCompleted");
    }

    @Override
    public void onWorkflowFailed(WorkflowFailedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowFailed " + ev.cause());
    }

    @Override
    public void onWorkflowCancelled(WorkflowCancelledEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowCancelled");
    }

    @Override
    public void onTaskStarted(TaskStartedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskStarted " + ev.taskContext().position().jsonPointer());
    }

    @Override
    public void onTaskCompleted(TaskCompletedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskCompleted " + ev.taskContext().position().jsonPointer());
    }

    @Override
    public void onTaskFailed(TaskFailedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskFailed " + ev.taskContext().position().jsonPointer()
                + " " + ev.cause());
    }
}
