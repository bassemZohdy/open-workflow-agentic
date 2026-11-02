package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.lifecycle.TaskStartedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionCompletableListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowStartedEvent;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Characterizes the pinned SDK in memory only; this is not a persistence or multi-replica safety proof. */
class PendingCancelSdkTest {
    private static final String DOCUMENT = """
            document: { dsl: '1.0.3', namespace: agentic-test, name: cancel-pending, version: '1.0.0' }
            do:
              - first:
                  set: { result: executed }
            """;

    @Test void synchronousStartListenerCanCancelBeforeAnyTaskAndTheSdkRemovesTheInstance() throws Exception {
        var instances = new ConcurrentHashMap<String, WorkflowInstance>();
        var starts = new AtomicInteger();
        var listener = new WorkflowExecutionCompletableListener() {
            @Override public CompletableFuture<?> onWorkflowStarted(WorkflowStartedEvent event) {
                var instance = instances.get(event.workflowContext().instanceData().id());
                assertNotNull(instance);
                assertTrue(instance.cancel(), "the SDK has transitioned to RUNNING before this callback");
                throw new CancellationException("stop before task execution");
            }
            @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                starts.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
        };
        try (var app = WorkflowApplication.builder().withListener(listener).disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            assertEquals(WorkflowStatus.PENDING, instance.status());
            assertFalse(instance.cancel(), "negative control: pending instances cannot be cancelled directly");
            instances.put(instance.id(), instance);
            var completion = instance.start();
            assertThrows(Exception.class, () -> completion.get(5, TimeUnit.SECONDS));
            assertEquals(0, starts.get());
            assertEquals(WorkflowStatus.CANCELLED, instance.status());
            assertTrue(definition.activeInstance(instance.id()).isEmpty(), "SDK cleanup removes the instance");
        }
    }

    @Test void failedListenerFutureAloneDoesNotStopTheSdkFromExecutingTasks() throws Exception {
        var starts = new AtomicInteger();
        var listener = new WorkflowExecutionCompletableListener() {
            @Override public CompletableFuture<?> onWorkflowStarted(WorkflowStartedEvent event) {
                return CompletableFuture.failedFuture(new IllegalStateException("failed gate"));
            }
            @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                starts.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
        };
        try (var app = WorkflowApplication.builder().withListener(listener).disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            instance.start().get(5, TimeUnit.SECONDS);
            assertEquals(1, starts.get(), "negative control: an exceptional listener future is swallowed");
            assertEquals(WorkflowStatus.COMPLETED, instance.status());
        }
    }

    @Test void skippedTasksNeverReachTheTaskStartedCallback() throws Exception {
        var starts = new AtomicInteger();
        var listener = new WorkflowExecutionCompletableListener() {
            @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                starts.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
        };
        String skipped = DOCUMENT.replace("  - first:\n", "  - first:\n      if: ${ false }\n");
        assertNotEquals(DOCUMENT, skipped);
        try (var app = WorkflowApplication.builder().withListener(listener).disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(skipped, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            instance.start().get(5, TimeUnit.SECONDS);
            assertEquals(0, starts.get(), "a task-start hook is not a universal pre-execution fence");
            assertEquals(WorkflowStatus.COMPLETED, instance.status());
        }
    }
}
