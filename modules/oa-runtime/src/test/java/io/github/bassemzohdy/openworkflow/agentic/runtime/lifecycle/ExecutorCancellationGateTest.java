package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskBase;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.executors.*;
import io.serverlessworkflow.impl.expressions.ExpressionDescriptor;
import io.serverlessworkflow.impl.expressions.jq.JQExpressionFactory;
import io.serverlessworkflow.impl.lifecycle.*;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Experimental SDK boundary only: no registry, persistence, process-loss or cross-replica guarantee. */
class ExecutorCancellationGateTest {
    private static final String DOCUMENT = """
            document: { dsl: '1.0.3', namespace: agentic-test, name: executor-cancel, version: '1.0.0' }
            do:
              - first:
                  if: ${ false }
                  set: { result: skipped }
              - second:
                  set: { result: executed }
            """;

    private static final class ExperimentalFactory implements TaskExecutorFactory {
        final Set<String> cancelled = ConcurrentHashMap.newKeySet();
        final AtomicInteger entries = new AtomicInteger();
        final TaskExecutorFactory delegate = DefaultTaskExecutorFactory.get();

        @Override public TaskExecutorBuilder<? extends TaskBase> getTaskExecutor(WorkflowMutablePosition position,
                Task task, WorkflowDefinition definition) {
            return new Wrapped<>(delegate.getTaskExecutor(position, task, definition));
        }
        private final class Wrapped<T extends TaskBase> implements TaskExecutorBuilder<T> {
            private final TaskExecutorBuilder<T> source;
            private Wrapped(TaskExecutorBuilder<T> source) { this.source = source; }
            @Override public void connect(Map<String, TaskExecutorBuilder<?>> builders) {
                // SDK transitions find the current builder by identity; preserve the original builders there.
                var original = new LinkedHashMap<String, TaskExecutorBuilder<?>>();
                builders.forEach((name, builder) -> original.put(name,
                        builder instanceof ExperimentalFactory.Wrapped<?> wrapped ? wrapped.source : builder));
                source.connect(original);
            }
            @Override public TaskExecutor<T> build() {
                var next = source.build();
                return (workflow, parent, input) -> {
                    if (!cancelled.contains(workflow.instanceData().id())) return next.apply(workflow, parent, input);
                    entries.incrementAndGet();
                    if (!workflow.instance().cancel())
                        return CompletableFuture.failedFuture(new IllegalStateException("cancellation was refused"));
                    return CompletableFuture.failedFuture(new CancellationException("cancel before task execution"));
                };
            }
        }
    }

    /** Test-only: production has other expression factories and a Quarkus-specific jq scope. */
    private static final class ExperimentalExpressions extends JQExpressionFactory {
        final Set<String> cancelled;
        final AtomicInteger entries = new AtomicInteger();
        ExperimentalExpressions(Set<String> cancelled) { this.cancelled = cancelled; }
        @Override public WorkflowFilter buildFilter(ExpressionDescriptor expression, WorkflowModelFactory models) {
            var next = super.buildFilter(expression, models);
            return (workflow, task, input) -> {
                if (task == null && cancelled.contains(workflow.instanceData().id())) {
                    entries.incrementAndGet();
                    if (!workflow.instance().cancel()) throw new IllegalStateException("cancellation was refused");
                    throw new CancellationException("cancel before input filter");
                }
                return next.apply(workflow, task, input);
            };
        }
    }

    @Test void anArmedInstanceCancelsBeforeEvenASkippedTaskAndIsRemoved() throws Exception {
        var factory = new ExperimentalFactory();
        var tasks = new AtomicInteger();
        var listener = new WorkflowExecutionCompletableListener() {
            @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                tasks.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
        };
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory).withListener(listener)
                .disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            factory.cancelled.add(instance.id());
            assertThrows(Exception.class, () -> instance.start().get(5, TimeUnit.SECONDS));
            assertEquals(1, factory.entries.get());
            assertEquals(0, tasks.get());
            assertEquals(WorkflowStatus.CANCELLED, instance.status());
            assertTrue(definition.activeInstance(instance.id()).isEmpty());
        }
    }

    @Test void anUnarmedWorkflowExecutesBothUnskippedTasks() throws Exception {
        var factory = new ExperimentalFactory();
        var tasks = new AtomicInteger();
        var listener = new WorkflowExecutionCompletableListener() {
            @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                tasks.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
        };
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory).withListener(listener)
                .disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(
                    DOCUMENT.replace("if: ${ false }", "if: ${ true }"), WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            instance.start().get(5, TimeUnit.SECONDS);
            assertEquals(0, factory.entries.get());
            assertEquals(2, tasks.get());
            assertEquals(WorkflowStatus.COMPLETED, instance.status());
        }
    }

    @Test void anUnarmedSkippedWorkflowMatchesTheOriginalSdk() throws Exception {
        int[] starts = new int[2];
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            var count = new AtomicInteger();
            var listener = new WorkflowExecutionCompletableListener() {
                @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                    count.incrementAndGet(); return CompletableFuture.completedFuture(null);
                }
            };
            var builder = WorkflowApplication.builder().withListener(listener).disableLifeCycleCEPublishing();
            if (i == 1) builder.withTaskExecutorFactory(new ExperimentalFactory());
            try (var app = builder.build()) {
                var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML));
                var instance = definition.instance(Map.of());
                instance.start().get(5, TimeUnit.SECONDS);
                assertEquals(WorkflowStatus.COMPLETED, instance.status());
                starts[i] = count.get(); outputs[i] = instance.output().toString();
            }
        }
        assertEquals(starts[0], starts[1]);
        assertEquals(outputs[0], outputs[1]);
    }

    @Test void aFailingWorkflowInputFilterRunsBeforeTheExecutorBoundary() throws Exception {
        var factory = new ExperimentalFactory();
        String filtered = DOCUMENT.replace("do:\n", "input:\n  from: '${ error(\"before gate\") }'\ndo:\n");
        assertNotEquals(DOCUMENT, filtered);
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory)
                .disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(filtered, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            factory.cancelled.add(instance.id());
            assertThrows(Exception.class, () -> instance.start().get(5, TimeUnit.SECONDS));
            assertEquals(0, factory.entries.get(), "the workflow input filter was evaluated before the guard");
            assertNotEquals(WorkflowStatus.CANCELLED, instance.status());
        }
    }

    @Test void anArmedInputFilterCanBeStoppedBeforeItIsEvaluated() throws Exception {
        var factory = new ExperimentalFactory();
        var expressions = new ExperimentalExpressions(factory.cancelled);
        String filtered = DOCUMENT.replace("do:\n", "input:\n  from: '${ error(\"filter executed\") }'\ndo:\n");
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory).withExpressionFactory(expressions)
                .disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(filtered, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            factory.cancelled.add(instance.id());
            assertThrows(Exception.class, () -> instance.start().get(5, TimeUnit.SECONDS));
            assertEquals(1, expressions.entries.get());
            assertEquals(0, factory.entries.get(), "the executor must not be reached");
            assertEquals(WorkflowStatus.CANCELLED, instance.status());
            assertTrue(definition.activeInstance(instance.id()).isEmpty());
        }
    }

    @Test void anUnarmedInputFilterStillTransformsTheWorkflowInput() throws Exception {
        var factory = new ExperimentalFactory();
        var expressions = new ExperimentalExpressions(factory.cancelled);
        String filtered = DOCUMENT.replace("do:\n", "input:\n  from: ${ .payload }\ndo:\n")
                .replace("set: { result: executed }", "set: { result: '${ .original }' }");
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory).withExpressionFactory(expressions)
                .disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(filtered, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of("payload", Map.of("original", "kept")));
            var result = instance.start().get(5, TimeUnit.SECONDS).asMap().orElseThrow();
            assertEquals("kept", result.get("result"));
            assertEquals(0, expressions.entries.get());
        }
    }
}
