package io.github.bassemzohdy.openworkflow.spike;

import io.quarkus.arc.Unremovable;
import io.serverlessworkflow.impl.lifecycle.TaskCancelledEvent;
import io.serverlessworkflow.impl.lifecycle.TaskCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.TaskFailedEvent;
import io.serverlessworkflow.impl.lifecycle.TaskRetriedEvent;
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
 * Records lifecycle events per instance so tests can assert on failures, retries, cancellation
 * and the thread a task ran on, without parsing logs. In-memory only: process tests read it over HTTP.
 */
@ApplicationScoped
@Unremovable
public class ObservingListener implements WorkflowExecutionListener {

    private static final Map<String, List<String>> EVENTS = new ConcurrentHashMap<>();

    public static List<String> eventsOf(String instanceId) {
        return List.copyOf(EVENTS.getOrDefault(instanceId, List.of()));
    }

    public static void clear() {
        EVENTS.clear();
    }

    private static void record(String instanceId, String what) {
        EVENTS.computeIfAbsent(instanceId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(what);
    }

    private static String thread() {
        return "thread=" + Thread.currentThread().getName();
    }

    @Override
    public void onWorkflowStarted(WorkflowStartedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowStarted " + thread());
    }

    @Override
    public void onWorkflowCompleted(WorkflowCompletedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowCompleted");
    }

    @Override
    public void onWorkflowFailed(WorkflowFailedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowFailed " + describe(ev.cause()));
    }

    @Override
    public void onWorkflowCancelled(WorkflowCancelledEvent ev) {
        record(ev.workflowContext().instanceData().id(), "WorkflowCancelled");
    }

    @Override
    public void onTaskStarted(TaskStartedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskStarted " + ev.taskContext().position().jsonPointer()
                + " " + thread());
    }

    @Override
    public void onTaskCompleted(TaskCompletedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskCompleted " + ev.taskContext().position().jsonPointer());
    }

    @Override
    public void onTaskFailed(TaskFailedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskFailed " + ev.taskContext().position().jsonPointer()
                + " " + describe(ev.cause()));
    }

    @Override
    public void onTaskCancelled(TaskCancelledEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskCancelled " + ev.taskContext().position().jsonPointer());
    }

    @Override
    public void onTaskRetried(TaskRetriedEvent ev) {
        record(ev.workflowContext().instanceData().id(), "TaskRetried " + ev.taskContext().position().jsonPointer()
                + " attempt=" + ev.taskContext().retryAttempt());
    }

    private static String describe(Throwable t) {
        return t == null ? "null" : t.getClass().getName() + ": " + t.getMessage();
    }
}
