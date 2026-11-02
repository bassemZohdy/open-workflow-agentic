package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.api.types.TaskBase;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.executors.DefaultTaskExecutorFactory;
import io.serverlessworkflow.impl.lifecycle.TaskStartedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowStartedEvent;
import io.serverlessworkflow.impl.WorkflowContextData;
import io.serverlessworkflow.impl.WorkflowContext;
import io.serverlessworkflow.impl.executors.AbstractTaskExecutor;
import io.serverlessworkflow.impl.executors.TaskExecutor;
import io.serverlessworkflow.impl.executors.TaskExecutorBuilder;
import io.serverlessworkflow.impl.executors.TaskExecutorFactory;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnerFencedTaskExecutorFactoryTest {
    private static final String TWO_TASKS = """
            document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
            do:
              - first:
                  set: { first: true }
              - second:
                  set: { second: true }
            """;
    @Test void lossBetweenSequentialTasksRefusesTheSecondTask() throws Exception {
        var checks = new AtomicInteger(); var started = new AtomicInteger();
        var factory = new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), context -> {
            if (checks.incrementAndGet() == 2) throw new IllegalStateException("owner lost");
        });
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory)
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onTaskStarted(TaskStartedEvent event) { started.incrementAndGet(); }
                }).disableLifeCycleCEPublishing().build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(TWO_TASKS, WorkflowFormat.YAML)).instance(Map.of());
            assertThrows(Exception.class, () -> instance.start().get(5, TimeUnit.SECONDS));
            assertEquals(2, checks.get());
            assertEquals(1, started.get(), "the second task must not start after ownership is lost");
        }
    }
    @Test void currentOwnerExecutesSequentialTasksAndResumeLookupIsGuarded() throws Exception {
        var valid = new AtomicBoolean(true); var checks = new AtomicInteger();
        var context = new AtomicReference<WorkflowContext>();
        var factory = new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), workflow -> {
            checks.incrementAndGet(); if (!valid.get()) throw new IllegalStateException("owner lost");
        });
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory)
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onWorkflowStarted(WorkflowStartedEvent event) {
                        context.set((WorkflowContext) event.workflowContext());
                    }
                }).disableLifeCycleCEPublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(TWO_TASKS, WorkflowFormat.YAML));
            var instance = definition.instance(Map.of());
            instance.start().get(5, TimeUnit.SECONDS);
            assertEquals(2, checks.get());
            var restoredTarget = definition.taskExecutor("do/1/second");
            assertNotNull(restoredTarget);
            assertInstanceOf(AbstractTaskExecutor.class, restoredTarget, "the JPA writer requires an abstract SDK executor");
            valid.set(false);
            assertThrows(Exception.class, () -> restoredTarget.apply(context.get(), Optional.empty(),
                    context.get().instanceData().input()).get(5, TimeUnit.SECONDS));
            assertEquals(3, checks.get(), "lookup used for restored transitions must be guarded");
        }
    }

    @Test void unsupportedBuilderFailsClosedInsteadOfReturningAnUnguardedExecutor() {
        TaskExecutorFactory unsupported = (position, task, definition) -> new TaskExecutorBuilder<TaskBase>() {
            @Override public void connect(Map<String, TaskExecutorBuilder<?>> connections) { }
            @Override public TaskExecutor<TaskBase> build() { throw new AssertionError("must not build"); }
        };
        var factory = new OwnerFencedTaskExecutorFactory(unsupported, workflow -> { });
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory).disableLifeCycleCEPublishing().build()) {
            assertThrows(RuntimeException.class,
                    () -> app.workflowDefinition(WorkflowReader.readWorkflowFromString(TWO_TASKS, WorkflowFormat.YAML)));
        }
    }

    @Test void skippedTasksStillCrossTheOwnerBoundary() throws Exception {
        var checks = new AtomicInteger(); var started = new AtomicInteger();
        var factory = new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), workflow -> checks.incrementAndGet());
        String document = TWO_TASKS.replace("- first:\n", "- first:\n      if: ${ false }\n");
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory)
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onTaskStarted(TaskStartedEvent event) { started.incrementAndGet(); }
                }).disableLifeCycleCEPublishing().build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML)).instance(Map.of());
            instance.start().get(5, TimeUnit.SECONDS);
            assertEquals(2, checks.get()); assertEquals(1, started.get());
        }
    }

    @Test void nestedTaskTransitionsRemainGuarded() throws Exception {
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-nested, version: '1.0.0' }
                do:
                  - group:
                      do:
                        - first:
                            set: { first: true }
                        - second:
                            set: { second: true }
                """;
        var checks = new AtomicInteger(); var names = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var factory = new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), workflow -> {
            if (checks.incrementAndGet() == 3) throw new IllegalStateException("owner lost");
        });
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory)
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onTaskStarted(TaskStartedEvent event) { names.add(event.taskContext().taskName()); }
                }).disableLifeCycleCEPublishing().build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML)).instance(Map.of());
            assertThrows(Exception.class, () -> instance.start().get(5, TimeUnit.SECONDS));
            assertEquals(3, checks.get()); assertFalse(names.contains("second"));
        }
    }

    @Test void namedJumpCannotBypassTheNextOwnerCheck() throws Exception {
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-jump, version: '1.0.0' }
                do:
                  - first:
                      set: { first: true }
                      then: third
                  - second:
                      set: { wrong: true }
                  - third:
                      set: { third: true }
                """;
        var checks = new AtomicInteger(); var started = new AtomicInteger();
        var factory = new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), workflow -> {
            if (checks.incrementAndGet() == 2) throw new IllegalStateException("owner lost");
        });
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory)
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onTaskStarted(TaskStartedEvent event) { started.incrementAndGet(); }
                }).disableLifeCycleCEPublishing().build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML)).instance(Map.of());
            assertThrows(Exception.class, () -> instance.start().get(5, TimeUnit.SECONDS));
            assertEquals(2, checks.get()); assertEquals(1, started.get());
        }
    }

    @Test void namedSelfJumpChecksOwnershipOnEveryIteration() throws Exception {
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-loop, version: '1.0.0' }
                do:
                  - again:
                      set: { value: true }
                      then: again
                """;
        var checks = new AtomicInteger();
        var factory = new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), workflow -> {
            if (checks.incrementAndGet() == 3) throw new IllegalStateException("owner lost");
        });
        try (var app = WorkflowApplication.builder().withTaskExecutorFactory(factory).disableLifeCycleCEPublishing().build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML)).instance(Map.of());
            assertThrows(Exception.class, () -> instance.start().get(5, TimeUnit.SECONDS));
            assertEquals(3, checks.get());
        }
    }

}
