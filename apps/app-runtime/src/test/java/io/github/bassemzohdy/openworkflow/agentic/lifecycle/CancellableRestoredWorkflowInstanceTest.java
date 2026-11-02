package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CancellablePersistenceReader;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CheckpointingWriter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PersistenceCommitGate;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
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
import io.serverlessworkflow.impl.persistence.PersistenceInstanceOperations;
import io.serverlessworkflow.impl.persistence.PersistenceWorkflowInfo;
import io.serverlessworkflow.impl.persistence.WorkflowPersistenceListener;
import jakarta.inject.Inject;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Same-process SDK/JPA proof. No production reader, process-failure or multi-replica safety is asserted. */
@QuarkusTest
class CancellableRestoredWorkflowInstanceTest {
    private static final String DOCUMENT = """
        document: { dsl: '1.0.3', namespace: agentic-test, name: guarded-restore, version: '1.0.0' }
        do:
          - wait:
              listen:
                to:
                  one:
                    with: { type: io.openworkflow.test.never }
        """;
    @Inject JpaInstanceWriter writer;
    @Inject JpaInstanceOperations operations;
    @Inject DataSource data;

    private PersistenceInstanceWriter borrowed() {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(writer, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }
    interface Scenario { void run(WorkflowInstance instance, WorkflowDefinition definition, String appId,
                                  CancellablePersistenceReader reader) throws Exception; }
    void withRestored(java.util.function.Function<PersistenceInstanceWriter, PersistenceInstanceWriter> wrap, Scenario scenario)
            throws Exception { withRestored(wrap, java.util.function.UnaryOperator.identity(), scenario); }
    void withRestored(java.util.function.Function<PersistenceInstanceWriter, PersistenceInstanceWriter> wrap,
                      java.util.function.UnaryOperator<PersistenceInstanceOperations> operationsWrap, Scenario scenario)
            throws Exception {
        String appId = "guarded-restore-" + UUID.randomUUID();
        var listening = new CountDownLatch(1);
        var broker = new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var result = super.register(builder, callback, workflow, task); listening.countDown(); return result;
            }
        };
        try (var firstGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC());
             var secondGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC());
             var first = WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(), firstGate, (op, id) -> {}))).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML);
            var original = first.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(listening.await(10, TimeUnit.SECONDS));
            var started = new AtomicInteger();
            var cancelled = new AtomicInteger();
            var listener = new WorkflowExecutionCompletableListener() {
                @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                    started.incrementAndGet(); return CompletableFuture.completedFuture(null);
                }
                @Override public CompletableFuture<?> onWorkflowCancelled(WorkflowCancelledEvent event) {
                    cancelled.incrementAndGet(); return CompletableFuture.completedFuture(null);
                }
            };
            var guarded = new CheckpointingWriter(borrowed(), secondGate, (op, id) -> {});
            try (var restored = WorkflowApplication.builder().withId(appId)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing().withListener(listener)
                    .withListener(new WorkflowPersistenceListener(guarded)).build()) {
                var definition = restored.workflowDefinition(workflow);
                var reader = new CancellablePersistenceReader(operationsWrap.apply(operations), wrap.apply(guarded));
                var instance = reader.find(definition, original.id()).orElseThrow();
                assertSame(instance, reader.find(definition, original.id()).orElseThrow());
                try (var all = reader.scanAll(definition, appId)) {
                    assertSame(instance, all.filter(found -> found.id().equals(original.id())).findFirst().orElseThrow());
                }
                assertEquals(WorkflowStatus.PENDING, instance.status());
                assertThrows(CompletionException.class,
                        () -> ((WorkflowMutableInstance) instance).status(WorkflowStatus.RUNNING).join());
                assertEquals(WorkflowStatus.PENDING, instance.status());
                scenario.run(instance, definition, appId, reader);
                assertEquals(0, started.get(), "no task may start through a stale restored holder");
                assertEquals(instance.status() == WorkflowStatus.CANCELLED ? 1 : 0, cancelled.get());
            }
        }
    }
    long persisted(String appId, String id) throws Exception {
        try (var c = data.getConnection(); var q = c.prepareStatement(
                "select count(*) from workflowinstanceentity where applicationid=? and instanceid=?")) {
            q.setString(1, appId); q.setString(2, id);
            try (var rows = q.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
    @Test void nonCancelledRestoredInstanceResumesItsCheckpoint() throws Exception {
        String appId = "guarded-resume-" + UUID.randomUUID();
        var originalListening = new CountDownLatch(1); var restoredListening = new CountDownLatch(1);
        var originalBroker = new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var result = super.register(builder, callback, workflow, task); originalListening.countDown(); return result;
            }
        };
        var restoredBroker = new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var result = super.register(builder, callback, workflow, task); restoredListening.countDown(); return result;
            }
        };
        try (var firstGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC());
             var secondGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC());
             var first = WorkflowApplication.builder().withId(appId).withEventConsumer(originalBroker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(), firstGate, (op,id) -> {}))).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML);
            var original = first.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(originalListening.await(10, TimeUnit.SECONDS));
            var guarded = new CheckpointingWriter(borrowed(), secondGate, (op,id) -> {});
            try (var restored = WorkflowApplication.builder().withId(appId).withEventConsumer(restoredBroker)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(guarded)).build()) {
                var definition = restored.workflowDefinition(workflow);
                var reader = new CancellablePersistenceReader(operations, guarded);
                var instance = reader.find(definition, original.id()).orElseThrow();
                instance.start();
                assertTrue(restoredListening.await(10, TimeUnit.SECONDS), "restored listen task must run");
                assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
                assertEquals(0, persisted(appId, instance.id()));
            }
        }
    }

    @Test void acknowledgedCancellationRemovesCheckpointAndStaleHolderCannotStart() throws Exception {
        withRestored(writer -> writer, (instance, definition, appId, reader) -> {
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(WorkflowStatus.CANCELLED, instance.status());
            assertTrue(definition.activeInstance(instance.id()).isEmpty());
            assertEquals(0, persisted(appId, instance.id()));
            assertThrows(CancellationException.class, () -> instance.start().join());
        });
    }
    @Test void pendingRemovalCannotBeForgedAndStaleHolderCannotStart() throws Exception {
        var release = new CompletableFuture<Void>();
        withRestored(real -> (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("aborted"))
                        return release.thenCompose(ignored -> {
                            try { return (CompletableFuture<Void>) method.invoke(real, args); }
                            catch (ReflectiveOperationException e) { return CompletableFuture.failedFuture(e); }
                        });
                    try { return method.invoke(real, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                }), (instance, definition, appId, reader) -> {
            var callerView = instance.cancelFuture(); callerView.complete(true);
            assertFalse(instance.cancelFuture().isDone());
            assertThrows(CompletionException.class,
                    () -> ((WorkflowMutableInstance) instance).status(WorkflowStatus.CANCELLED).join());
            assertThrows(CompletionException.class,
                    () -> ((WorkflowMutableInstance) instance).status(WorkflowStatus.RUNNING).join());
            assertEquals(WorkflowStatus.PENDING, instance.status());
            assertEquals(1, persisted(appId, instance.id()));
            assertThrows(CancellationException.class, () -> instance.start().join());
            release.complete(null);
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(0, persisted(appId, instance.id()));
            assertTrue(definition.activeInstance(instance.id()).isEmpty());
        });
    }
    @Test void staleReaderSnapshotsCannotRecreateACancelledInstance() throws Exception {
        var stale = new java.util.concurrent.atomic.AtomicBoolean();
        var saved = new java.util.concurrent.atomic.AtomicReference<PersistenceWorkflowInfo>();
        java.util.function.UnaryOperator<PersistenceInstanceOperations> snapshot = real ->
                (PersistenceInstanceOperations) Proxy.newProxyInstance(PersistenceInstanceOperations.class.getClassLoader(),
                        new Class<?>[]{PersistenceInstanceOperations.class}, (proxy, method, args) -> {
                            if (stale.get() && method.getName().equals("readWorkflowInfo")) return Optional.of(saved.get());
                            if (stale.get() && method.getName().equals("scanAll")) return java.util.stream.Stream.of(saved.get());
                            try {
                                Object result = method.invoke(real, args);
                                if (method.getName().equals("readWorkflowInfo")) {
                                    @SuppressWarnings("unchecked") var found = (Optional<PersistenceWorkflowInfo>) result;
                                    found.ifPresent(saved::set);
                                }
                                return result;
                            } catch (InvocationTargetException error) { throw error.getCause(); }
                        });
        withRestored(writer -> writer, snapshot, (instance, definition, appId, reader) -> {
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            stale.set(true);
            assertTrue(reader.find(definition, instance.id()).isEmpty());
            try (var all = reader.scanAll(definition, appId)) { assertEquals(0, all.count()); }
            assertTrue(definition.activeInstance(instance.id()).isEmpty());
            assertThrows(CancellationException.class, () -> instance.start().join());
        });
    }

    @Test void failedRemovalLeavesCheckpointAndRefusesStaleStart() throws Exception {
        withRestored(real -> (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("aborted")) return CompletableFuture.failedFuture(new IllegalStateException("store down"));
                    try { return method.invoke(real, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                }), (instance, definition, appId, reader) -> {
            assertThrows(ExecutionException.class, () -> instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(WorkflowStatus.PENDING, instance.status());
            assertEquals(1, persisted(appId, instance.id()));
            assertThrows(CancellationException.class, () -> instance.start().join());
        });
    }
}
