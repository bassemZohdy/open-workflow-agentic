package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.OwnerFencedJpaWriter;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceReader;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.executors.DefaultTaskExecutorFactory;
import io.serverlessworkflow.impl.expressions.ExpressionDescriptor;
import io.serverlessworkflow.impl.expressions.jq.JQExpressionFactory;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.persistence.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.function.Consumer;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Real SDK execution boundaries backed by V82 in a disposable database. No production wiring or external-effect proof. */
@QuarkusTest
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowOwnerExecutionBoundaryTest {
    private static final String DOCUMENT = """
            document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
            do:
              - first:
                  set: { first: true }
              - second:
                  set: { second: true }
            """;
    @Inject DataSource data;
    @Inject JdbcInstanceStore store;
    @Inject WorkflowOwnerContextBindings bindings;
    @Inject WorkflowOwnerExecutionFence ownerFence;
    @Inject JpaInstanceReader reader;
    @Inject JpaInstanceWriter writer;
    @Inject OwnerFencedJpaWriter fencedWriter;

    @BeforeAll void installProposal() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/proposed/V82__workflow_owner_lease.sql")) {
            assertNotNull(in);
            try (var c = data.getConnection(); var s = c.createStatement()) {
                s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
    private final class GuardedExpressions extends JQExpressionFactory {
        final AtomicInteger evaluated = new AtomicInteger();
        @Override public int priority(ExpressionDescriptor expression) { return 999; }
        @Override public WorkflowFilter buildFilter(ExpressionDescriptor expression, WorkflowModelFactory models) {
            var delegate = super.buildFilter(expression, models);
            return OwnerFencedWorkflowFilter.wrap((workflow, task, input) -> {
                if (task == null) evaluated.incrementAndGet();
                return delegate.apply(workflow, task, input);
            }, ownerFence::requireCurrent);
        }
    }
    private JdbcWorkflowOwnerLease.Binding register(String appId, String tenant, WorkflowInstance instance) {
        var actor = new Subject(SubjectKind.USER, "execution-boundary-test");
        var definition = new DefinitionRef("agentic-test/owner-boundary", "1.0.0");
        var request = new StartRequest(tenant, actor, definition, Map.of(), "owner-boundary-" + UUID.randomUUID());
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
        row = store.replace(row.successor(now, InstanceStatus.RUNNING, instance.id(), 0, null, null), row.version(), "test.running", actor);
        return new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), appId, instance.id(), definition);
    }

    @Test void restoredWaitDoesNotAdvanceAfterAnotherOwnerTakesOver() throws Exception { runRestoredWait(true); }
    @Test void currentOwnerCanFinishTheRestoredWait() throws Exception { runRestoredWait(false); }

    private void runRestoredWait(boolean takeover) throws Exception {
        String appId = "owner-restore-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
                do:
                  - prepare:
                      set: { prepared: true }
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: io.openworkflow.owner.resume }
                  - effect:
                      set: { effect: true }
                """;
        var firstWaiting = new CountDownLatch(1); var restoredWaiting = new CountDownLatch(1);
        var initialBroker = broker(firstWaiting); var restoredBroker = broker(restoredWaiting);
        var staleWrite = new AtomicReference<CompletableFuture<Void>>();
        var effectStarted = new AtomicBoolean();
        PersistenceInstanceWriter originalWriter = borrowed(writer, null);
        PersistenceInstanceWriter restoredWriter = borrowed(fencedWriter, staleWrite);
        try (initialBroker; restoredBroker;
             var originalApp = WorkflowApplication.builder().withId(appId).withEventConsumer(initialBroker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(originalWriter)).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML);
            var original = originalApp.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(firstWaiting.await(10, TimeUnit.SECONDS));
            assertEquals(1, taskCount(appId, original.id(), "do/0/prepare"));
            var binding = register(appId, tenant, original); var leases = new JdbcWorkflowOwnerLease(data);
            var first = leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow();
            OwnerFencedTaskExecutorFactory.Check oldOwner = context -> {
                bindings.bind(context, first.claim()); // Test-only trusted composition; restored contexts emit no started event.
                ownerFence.requireCurrent(context);
            };
            try (var restoredApp = WorkflowApplication.builder().withId(appId).withEventConsumer(restoredBroker)
                    .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), oldOwner))
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(restoredWriter))
                    .withListener(new WorkflowExecutionListener() {
                        @Override public void onTaskStarted(TaskStartedEvent event) {
                            if (event.taskContext().taskName().equals("effect")) effectStarted.set(true);
                        }
                    }).build()) {
                var definition = restoredApp.workflowDefinition(workflow);
                var restored = reader.find(definition, original.id()).orElseThrow();
                var completion = restored.start();
                assertTrue(restoredWaiting.await(10, TimeUnit.SECONDS), "restored wait must be active under its owner");
                if (takeover) {
                    assertTrue(leases.release(first.claim()));
                    assertTrue(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).isPresent());
                }
                restoredBroker.publish(CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                        .withSource(URI.create("urn:owner:restore-test")).withType("io.openworkflow.owner.resume").build())
                        .get(10, TimeUnit.SECONDS);
                if (takeover) {
                    assertThrows(Exception.class, () -> completion.get(10, TimeUnit.SECONDS));
                    assertFalse(effectStarted.get(), "the stale restored context must not start the next task");
                    assertNotNull(staleWrite.get());
                    var refused = assertThrows(ExecutionException.class, () -> staleWrite.get().get(10, TimeUnit.SECONDS));
                    assertTrue(hasMessage(refused, "workflow owner lease is not current"),
                            "the owner fence must reject the checkpoint, not an unrelated persistence failure");
                    assertEquals(0, taskCount(appId, original.id(), "do/1/wait"), "stale wait checkpoint must not commit");
                    assertEquals(0, taskCount(appId, original.id(), "do/2/effect"));
                } else {
                    completion.get(10, TimeUnit.SECONDS);
                    assertTrue(effectStarted.get(), "the current owner should continue after the restored wait");
                    assertNotNull(staleWrite.get());
                    staleWrite.get().get(10, TimeUnit.SECONDS);
                    assertEquals(0, taskCount(appId, original.id(), "do/0/prepare"),
                            "completed workflow cleanup should remove its SDK checkpoints");
                }
            }
        }
    }

    private static boolean hasMessage(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (String.valueOf(cause.getMessage()).contains(text)) return true;
        return false;
    }

    private static InMemoryEvents broker(CountDownLatch waiting) {
        return new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var registration = super.register(builder, callback, workflow, task); waiting.countDown(); return registration;
            }
        };
    }

    private static PersistenceInstanceWriter borrowed(PersistenceInstanceWriter source,
                                                       AtomicReference<CompletableFuture<Void>> taskWrite) {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    try {
                        Object result = method.invoke(source, args);
                        if (taskWrite != null && method.getName().equals("taskCompleted")) {
                            @SuppressWarnings("unchecked") var future = (CompletableFuture<Void>) result;
                            taskWrite.set(future);
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }

    private long taskCount(String appId, String engineId, String position) throws Exception {
        try (var c = data.getConnection(); var q = c.prepareStatement("select count(*) from public.taskinfoentity "
                + "where applicationid=? and workflowinstanceid=? and jsonpointer=?")) {
            q.setString(1, appId); q.setString(2, engineId); q.setString(3, position);
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    @Test void currentOwnerPassesRootFilterAndBothTaskBoundaries() throws Exception {
        String appId = "owner-exec-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>(); var starts = new AtomicInteger();
        var expressions = new GuardedExpressions();
        String document = DOCUMENT.replace("do:\n", "input:\n  from: ${ .payload }\ndo:\n");
        try (var app = WorkflowApplication.builder().withId(appId)
                .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                .withExpressionFactory(expressions).disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onWorkflowStarted(WorkflowStartedEvent event) { bindings.bind(event.workflowContext(), claim.get()); }
                    @Override public void onTaskStarted(TaskStartedEvent event) { starts.incrementAndGet(); }
                }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML))
                    .instance(Map.of("payload", Map.of("original", "kept")));
            var binding = register(appId, tenant, instance);
            claim.set(new JdbcWorkflowOwnerLease(data).acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow().claim());
            instance.start().get(10, TimeUnit.SECONDS);
            assertEquals(1, expressions.evaluated.get()); assertEquals(2, starts.get());
        }
    }

    @Test void staleOwnerCannotEvaluateTheWorkflowInputFilter() throws Exception {
        String appId = "owner-input-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>(); var starts = new AtomicInteger();
        var expressions = new GuardedExpressions();
        String document = DOCUMENT.replace("do:\n", "input:\n  from: '${ error(\"input filter ran\") }'\ndo:\n");
        try (var app = WorkflowApplication.builder().withId(appId)
                .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                .withExpressionFactory(expressions).disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onWorkflowStarted(WorkflowStartedEvent event) { bindings.bind(event.workflowContext(), claim.get()); }
                    @Override public void onTaskStarted(TaskStartedEvent event) { starts.incrementAndGet(); }
                }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML)).instance(Map.of());
            var binding = register(appId, tenant, instance); var leases = new JdbcWorkflowOwnerLease(data);
            var first = leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow(); claim.set(first.claim());
            assertTrue(leases.release(first.claim()));
            assertTrue(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).isPresent());
            assertThrows(Exception.class, () -> instance.start().get(10, TimeUnit.SECONDS));
            assertEquals(0, expressions.evaluated.get(), "the input filter itself must never run");
            assertEquals(0, starts.get());
        }
    }

    @Test void takeoverBetweenTasksRefusesTheNextTask() throws Exception {
        String appId = "owner-tasks-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>();
        var leaseBinding = new AtomicReference<JdbcWorkflowOwnerLease.Binding>();
        var taskNames = new CopyOnWriteArrayList<String>(); var takeover = new AtomicBoolean();
        var leases = new JdbcWorkflowOwnerLease(data);
        try (var app = WorkflowApplication.builder().withId(appId)
                .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onWorkflowStarted(WorkflowStartedEvent event) { bindings.bind(event.workflowContext(), claim.get()); }
                    @Override public void onTaskStarted(TaskStartedEvent event) { taskNames.add(event.taskContext().taskName()); }
                    @Override public void onTaskCompleted(TaskCompletedEvent event) {
                        if (event.taskContext().taskName().equals("first")) {
                            if (!leases.release(claim.get())) throw new IllegalStateException("old owner was not released");
                            if (leases.acquire(leaseBinding.get(), UUID.randomUUID(), Duration.ofSeconds(15)).isEmpty())
                                throw new IllegalStateException("successor was not acquired");
                            takeover.set(true);
                        }
                    }
                }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML)).instance(Map.of());
            var binding = register(appId, tenant, instance); leaseBinding.set(binding);
            claim.set(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow().claim());
            assertThrows(Exception.class, () -> instance.start().get(10, TimeUnit.SECONDS));
            assertTrue(takeover.get()); assertEquals(List.of("first"), taskNames);
        }
    }
}
