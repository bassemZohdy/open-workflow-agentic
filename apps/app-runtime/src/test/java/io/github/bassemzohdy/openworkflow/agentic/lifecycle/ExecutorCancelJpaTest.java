package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CheckpointingWriter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PersistenceCommitGate;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceReader;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskBase;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.InMemoryEvents;
import io.serverlessworkflow.impl.events.TypeEventRegistration;
import io.serverlessworkflow.impl.events.TypeEventRegistrationBuilder;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.executors.*;
import io.serverlessworkflow.impl.expressions.ExpressionDescriptor;
import io.serverlessworkflow.impl.expressions.jq.JQExpressionFactory;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceWriter;
import io.serverlessworkflow.impl.persistence.WorkflowPersistenceListener;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Experimental same-process restore; neither a process crash nor a multi-replica fence is implied. */
@QuarkusTest
class ExecutorCancelJpaTest {
    private static final String DOCUMENT = """
            document: { dsl: '1.0.3', namespace: agentic-test, name: cancel-pending-jpa, version: '1.0.0' }
            do:
              - wait:
                  listen:
                    to:
                      one:
                        with: { type: io.openworkflow.test.never }
            """;
    @Inject JpaInstanceWriter writer;
    @Inject JpaInstanceReader reader;
    @Inject DataSource dataSource;

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

    private PersistenceInstanceWriter borrowed() {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(writer, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    @Test void cancellingAtTheExecutorBoundaryRemovesTheRestoredPersistedInstance() throws Exception {
        cancelRestored(false);
    }

    @Test void cancellingBeforeTheInputFilterRemovesTheRestoredPersistedInstance() throws Exception {
        cancelRestored(true);
    }

    private void cancelRestored(boolean filtered) throws Exception {
        String appId = "pending-cancel-" + UUID.randomUUID();
        var listening = new CountDownLatch(1);
        var broker = new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var registration = super.register(builder, callback, workflow, task);
                listening.countDown(); return registration;
            }
        };
        try (var firstGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC());
             var first = WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(), firstGate, (op,id) -> {})))
                     .build();
             var secondGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC())) {
            String document = filtered ? DOCUMENT.replace("do:\n", "input:\n  from: ${ .payload }\ndo:\n") : DOCUMENT;
            var workflow = WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML);
            var original = first.workflowDefinition(workflow).instance(filtered ? Map.of("payload", Map.of()) : Map.of());
            original.start();
            assertTrue(listening.await(10, TimeUnit.SECONDS));
            var factory = new ExperimentalFactory();
            var expressions = new ExperimentalExpressions(factory.cancelled);
            var starts = new AtomicInteger();
            var registrations = new AtomicInteger();
            var aborted = new CompletableFuture<Void>();
            var guard = new WorkflowExecutionCompletableListener() {
                @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                    starts.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }
            };
            var restoredBroker = new InMemoryEvents() {
                @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                        Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                    registrations.incrementAndGet();
                    return super.register(builder, callback, workflow, task);
                }
            };
            try (var restoredApp = WorkflowApplication.builder().withId(appId).withEventConsumer(restoredBroker)
                    .withTaskExecutorFactory(factory)
                    .withExpressionFactory(expressions)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(), secondGate,
                            (op,id) -> { if (op == PersistenceCommitGate.Operation.ABORTED) aborted.complete(null); })))
                    .withListener(guard).build()) {
                var definition = restoredApp.workflowDefinition(workflow);
                var restored = reader.find(definition, original.id()).orElseThrow();
                assertEquals(WorkflowStatus.PENDING, restored.status());
                factory.cancelled.add(restored.id());
                var completion = restored.start();
                assertThrows(ExecutionException.class, () -> completion.get(10, TimeUnit.SECONDS));
                aborted.get(10, TimeUnit.SECONDS);
                assertEquals(filtered ? 1 : 0, expressions.entries.get());
                assertEquals(filtered ? 0 : 1, factory.entries.get());
                assertEquals(0, starts.get(), "the task-start callback must not be reached");
                assertEquals(0, registrations.get(), "the listen task body must not execute");
                assertEquals(WorkflowStatus.CANCELLED, restored.status());
                assertTrue(definition.activeInstance(restored.id()).isEmpty());
                try (var connection = dataSource.getConnection(); var query = connection.prepareStatement(
                        "select count(*) from workflowinstanceentity where applicationid=? and instanceid=?")) {
                    query.setString(1, appId); query.setString(2, original.id());
                    try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1)); }
                }
            }
        }
    }
}
