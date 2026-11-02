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
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.InMemoryEvents;
import io.serverlessworkflow.impl.events.TypeEventRegistration;
import io.serverlessworkflow.impl.events.TypeEventRegistrationBuilder;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceWriter;
import io.serverlessworkflow.impl.persistence.WorkflowPersistenceListener;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Experimental same-process restore; neither a process crash nor a multi-replica fence is implied. */
@QuarkusTest
class PendingCancelJpaTest {
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

    private PersistenceInstanceWriter borrowed() {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(writer, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    @Test void cancellingAtTheRestoredTaskBoundaryRemovesThePersistedInstance() throws Exception {
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
            var workflow = WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML);
            var original = first.workflowDefinition(workflow).instance(Map.of());
            original.start();
            assertTrue(listening.await(10, TimeUnit.SECONDS));
            var target = new AtomicReference<WorkflowInstance>();
            var starts = new AtomicInteger();
            var workflowStarts = new AtomicInteger();
            var registrations = new AtomicInteger();
            var aborted = new CompletableFuture<Void>();
            var entered = new CountDownLatch(1);
            var guard = new WorkflowExecutionCompletableListener() {
                @Override public CompletableFuture<?> onWorkflowStarted(WorkflowStartedEvent event) {
                    workflowStarts.incrementAndGet(); return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                    starts.incrementAndGet();
                    entered.countDown();
                    var instance = target.get();
                    assertEquals(instance.id(), event.workflowContext().instanceData().id());
                    assertTrue(instance.cancel());
                    throw new CancellationException("stop before task execution");
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
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(), secondGate,
                            (op,id) -> { if (op == PersistenceCommitGate.Operation.ABORTED) aborted.complete(null); })))
                    .withListener(guard).build()) {
                var definition = restoredApp.workflowDefinition(workflow);
                var restored = reader.find(definition, original.id()).orElseThrow();
                assertEquals(WorkflowStatus.PENDING, restored.status());
                target.set(restored);
                var completion = restored.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS), () -> "task callback not reached: " + secondGate.status());
                assertThrows(ExecutionException.class, () -> completion.get(10, TimeUnit.SECONDS));
                aborted.get(10, TimeUnit.SECONDS);
                assertEquals(0, workflowStarts.get(), "restored SDK instances do not emit workflow-started");
                assertEquals(1, starts.get());
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
