package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.OwnerFencedJpaWriter;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.executors.DefaultTaskExecutorFactory;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Explicit same-process composition against a disposable database; it is not production lifecycle wiring. */
@QuarkusTest
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowOwnerCoordinatorTest {
    private static final String DOCUMENT = """
            document: { dsl: '1.0.3', namespace: agentic-test, name: owner-coordinator, version: '1.0.0' }
            do:
              - wait:
                  listen:
                    to:
                      one:
                        with: { type: io.openworkflow.owner.coordinator }
              - effect:
                  set: { effect: true }
            """;
    @Inject DataSource data;
    @Inject JdbcInstanceStore store;
    @Inject TransactionManager transactions;
    @Inject WorkflowOwnerContextBindings bindings;
    @Inject WorkflowOwnerExecutionFence fence;
    @Inject OwnerFencedJpaWriter writer;

    @BeforeAll void installProposal() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/proposed/V82__workflow_owner_lease.sql")) {
            assertNotNull(in);
            try (var c = data.getConnection(); var s = c.createStatement()) {
                s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private record Running(WorkflowOwnerCoordinator coordinator, WorkflowOwnerCoordinator.Session session,
                           JdbcWorkflowOwnerLease.Binding binding, WorkflowContextData context,
                           InMemoryEvents broker, CompletableFuture<WorkflowModel> completion,
                           AtomicBoolean effectStarted, AtomicReference<CompletableFuture<Void>> taskWrite) { }
    @FunctionalInterface private interface Scenario { void run(Running running) throws Exception; }

    private void withWaitingOwner(Duration ttl, Scenario scenario) throws Exception {
        String appId = "owner-session-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var waiting = new CountDownLatch(1); var effectStarted = new AtomicBoolean();
        var binding = new AtomicReference<JdbcWorkflowOwnerLease.Binding>();
        var context = new AtomicReference<WorkflowContextData>();
        var session = new AtomicReference<WorkflowOwnerCoordinator.Session>();
        var taskWrite = new AtomicReference<CompletableFuture<Void>>();
        try (var coordinator = new WorkflowOwnerCoordinator(data, transactions, bindings, 8);
             var broker = new InMemoryEvents() {
                 @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                         Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                     var result = super.register(builder, callback, workflow, task); waiting.countDown(); return result;
                 }
             }) {
            PersistenceInstanceWriter composed = (PersistenceInstanceWriter) Proxy.newProxyInstance(
                    PersistenceInstanceWriter.class.getClassLoader(), new Class<?>[]{PersistenceInstanceWriter.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) return null;
                        if (method.getName().equals("started")) {
                            var ctx = (WorkflowContextData) args[0]; context.set(ctx);
                            session.set(coordinator.acquire(binding.get(), ctx, ttl).orElseThrow());
                        }
                        try {
                            Object result = method.invoke(writer, args);
                            if (method.getName().equals("taskCompleted")) {
                                @SuppressWarnings("unchecked") var future = (CompletableFuture<Void>) result;
                                taskWrite.set(future);
                            }
                            return result;
                        } catch (InvocationTargetException e) { throw e.getCause(); }
                    });
            try (var app = WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                    .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), fence::requireCurrent))
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(composed))
                    .withListener(new WorkflowExecutionListener() {
                        @Override public void onTaskStarted(TaskStartedEvent event) {
                            if (event.taskContext().taskName().equals("effect")) effectStarted.set(true);
                        }
                    }).build()) {
                var workflow = WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML);
                var instance = app.workflowDefinition(workflow).instance(Map.of());
                var actor = new Subject(SubjectKind.USER, "owner-session-test");
                var definition = new DefinitionRef("agentic-test/owner-coordinator", "1.0.0");
                var request = new StartRequest(tenant, actor, definition, Map.of(), "owner-session-" + UUID.randomUUID());
                var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
                row = store.replace(row.successor(now, InstanceStatus.RUNNING, instance.id(), 0, null, null), row.version(), "test.running", actor);
                binding.set(new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), appId, instance.id(), definition));
                var completion = instance.start();
                assertTrue(waiting.await(10, TimeUnit.SECONDS), "workflow must reach the wait under its acquired owner");
                assertNotNull(session.get()); assertEquals(WorkflowOwnerCoordinator.State.ACTIVE, session.get().state());
                assertEquals(1, count("select count(*) from public.workflowinstanceentity where instanceid=?", instance.id()));
                scenario.run(new Running(coordinator, session.get(), binding.get(), context.get(), broker, completion, effectStarted, taskWrite));
            }
        }
    }

    @Test void renewalKeepsTheOwnerLiveAndCloseRevokesItBeforeTakeover() throws Exception {
        withWaitingOwner(Duration.ofMillis(1200), running -> {
            var leases = new JdbcWorkflowOwnerLease(data); var original = running.session().claim();
            await(() -> count("select count(*) from task.workflow_owner_lease where instance_id=? "
                    + "and renewed_at > acquired_at + interval '500 milliseconds'", running.binding().instanceId().value()) == 1,
                    Duration.ofSeconds(8));
            assertEquals(WorkflowOwnerCoordinator.State.ACTIVE, running.session().state());
            assertTrue(leases.acquire(running.binding(), UUID.randomUUID(), Duration.ofSeconds(5)).isEmpty());
            assertTrue(running.coordinator().acquire(running.binding(), running.context(), Duration.ofSeconds(2)).isEmpty(),
                    "the same process cannot create a second owner session");
            running.session().close();
            assertEquals(WorkflowOwnerCoordinator.State.CLOSED, running.session().state());
            assertThrows(IllegalStateException.class, () -> bindings.require(running.context()));
            assertThrows(IllegalStateException.class, () -> bindings.bind(running.context(), original));
            await(() -> count("select count(*) from task.workflow_owner_lease where instance_id=? "
                    + "and expires_at <= clock_timestamp()", running.binding().instanceId().value()) == 1, Duration.ofSeconds(8));
            var successor = leases.acquire(running.binding(), UUID.randomUUID(), Duration.ofSeconds(5)).orElseThrow();
            assertEquals(original.epoch() + 1, successor.claim().epoch());
            assertFalse(leases.release(original));
            publish(running);
            assertThrows(Exception.class, () -> running.completion().get(10, TimeUnit.SECONDS));
            assertFalse(running.effectStarted().get());
        });
    }

    @Test void revocationWhileTheDatabaseCheckWaitsCannotReleaseTheTask() throws Exception {
        withWaitingOwner(Duration.ofSeconds(9), running -> {
            var original = running.session().claim();
            var worker = Executors.newSingleThreadExecutor();
            try (var lock = data.getConnection()) {
                lock.setAutoCommit(false);
                try (var q = lock.prepareStatement("select epoch from task.workflow_owner_lease where instance_id=? for update")) {
                    q.setString(1, running.binding().instanceId().value()); q.executeQuery().close();
                }
                var checking = worker.submit(() -> fence.requireCurrent(running.context()));
                await(() -> countWaitingLeaseChecks() > 0, Duration.ofSeconds(5));
                running.session().close();
                lock.commit();
                var failure = assertThrows(ExecutionException.class, () -> checking.get(5, TimeUnit.SECONDS));
                assertTrue(hasMessage(failure, "no current local owner generation"));
                assertDoesNotThrow(() -> new JdbcWorkflowOwnerLease(data).withCurrentLease(original, c -> null),
                        "the database claim must still be live when the post-check rejects local revocation");
            } finally { worker.shutdownNow(); }
        });
    }

    @Test void revocationWhileTheWriterWaitsRollsBackItsJpaChange() throws Exception {
        withWaitingOwner(Duration.ofSeconds(9), running -> {
            var original = running.session().claim();
            try (var lock = data.getConnection()) {
                lock.setAutoCommit(false);
                try (var q = lock.prepareStatement("select epoch from task.workflow_owner_lease where instance_id=? for update")) {
                    q.setString(1, running.binding().instanceId().value()); q.executeQuery().close();
                }
                var write = writer.suspended(running.context());
                await(() -> countWaitingLeaseChecks() > 0, Duration.ofSeconds(5));
                running.session().close();
                lock.commit();
                var failure = assertThrows(ExecutionException.class, () -> write.get(5, TimeUnit.SECONDS));
                assertTrue(hasMessage(failure, "no current local owner generation"));
                assertEquals(1, count("select count(*) from public.workflowinstanceentity where instanceid=? and status is null",
                        running.binding().engineInstanceId()), "the locally revoked writer must not commit its status change");
                assertDoesNotThrow(() -> new JdbcWorkflowOwnerLease(data).withCurrentLease(original, c -> null),
                        "the database claim must remain live while the local binding rejects the writer");
            }
        });
    }

    @Test void closingDuringBlockedAcquisitionNeverBindsOrStartsTheContext() throws Exception {
        String appId = "owner-close-race-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var context = new AtomicReference<WorkflowContextData>();
        var binding = new AtomicReference<JdbcWorkflowOwnerLease.Binding>();
        var result = new AtomicReference<Optional<WorkflowOwnerCoordinator.Session>>();
        var tasks = new AtomicInteger(); var worker = Executors.newSingleThreadExecutor();
        try (var coordinator = new WorkflowOwnerCoordinator(data, transactions, bindings, 2);
             var app = WorkflowApplication.builder().withId(appId)
                     .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), fence::requireCurrent))
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowExecutionListener() {
                         @Override public void onWorkflowStarted(WorkflowStartedEvent event) {
                             context.set(event.workflowContext());
                             result.set(coordinator.acquire(binding.get(), event.workflowContext(), Duration.ofSeconds(5)));
                         }
                         @Override public void onTaskStarted(TaskStartedEvent event) { tasks.incrementAndGet(); }
                     }).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML);
            var instance = app.workflowDefinition(workflow).instance(Map.of());
            var actor = new Subject(SubjectKind.USER, "owner-close-race");
            var definition = new DefinitionRef("agentic-test/owner-coordinator", "1.0.0");
            var request = new StartRequest(tenant, actor, definition, Map.of(), "owner-close-" + UUID.randomUUID());
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
            row = store.replace(row.successor(now, InstanceStatus.RUNNING, instance.id(), 0, null, null), row.version(), "test.running", actor);
            binding.set(new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), appId, instance.id(), definition));
            try (var lock = data.getConnection()) {
                lock.setAutoCommit(false);
                try (var q = lock.prepareStatement("select id from task.instance where id=? for update")) {
                    q.setString(1, row.id().value()); q.executeQuery().close();
                }
                Future<CompletableFuture<WorkflowModel>> started = worker.submit((Callable<CompletableFuture<WorkflowModel>>) instance::start);
                await(() -> countWaitingRegistryChecks() > 0, Duration.ofSeconds(5));
                assertTimeoutPreemptively(Duration.ofSeconds(1), coordinator::close,
                        "shutdown must not wait for an in-flight database acquisition");
                lock.commit();
                var completion = started.get(5, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> completion.get(5, TimeUnit.SECONDS));
                assertNotNull(result.get()); assertTrue(result.get().isEmpty());
                assertEquals(0, tasks.get());
                assertThrows(IllegalStateException.class, () -> bindings.require(context.get()));
                assertEquals(1, count("select count(*) from task.workflow_owner_lease where instance_id=? and state='ACTIVE'",
                        row.id().value()), "a late acknowledged lease must be left to expire");
            }
        } finally { worker.shutdownNow(); }
    }

    @Test void failedRenewalRevokesLocallyWhileTheDatabaseLeaseIsStillLive() throws Exception {
        withWaitingOwner(Duration.ofSeconds(9), running -> {
            var original = running.session().claim();
            try {
                try (var c = data.getConnection(); var s = c.createStatement()) {
                    s.execute("create function task.reject_owner_renewal() returns trigger language plpgsql as $$ "
                            + "begin if old.instance_id = '" + running.binding().instanceId().value() + "' "
                            + "and new.state = 'ACTIVE' and new.epoch = old.epoch then "
                            + "raise exception 'injected owner renewal failure'; end if; return new; end $$");
                    s.execute("create trigger reject_owner_renewal before update on task.workflow_owner_lease "
                            + "for each row execute function task.reject_owner_renewal()");
                }
                await(() -> running.session().state() == WorkflowOwnerCoordinator.State.LOST, Duration.ofSeconds(7));
                var leases = new JdbcWorkflowOwnerLease(data);
                assertDoesNotThrow(() -> leases.withCurrentLease(original, c -> null),
                        "the database lease must still be live to prove local revocation matters");
                assertThrows(IllegalStateException.class, () -> bindings.require(running.context()));
                assertThrows(ExecutionException.class, () -> writer.suspended(running.context()).get(5, TimeUnit.SECONDS));
                publish(running);
                assertThrows(Exception.class, () -> running.completion().get(10, TimeUnit.SECONDS));
                assertFalse(running.effectStarted().get(), "a locally revoked owner cannot start the next task");
                assertNotNull(running.taskWrite().get());
                assertThrows(ExecutionException.class, () -> running.taskWrite().get().get(5, TimeUnit.SECONDS));
            } finally {
                try (var c = data.getConnection(); var s = c.createStatement()) {
                    s.execute("drop trigger if exists reject_owner_renewal on task.workflow_owner_lease");
                    s.execute("drop function if exists task.reject_owner_renewal()");
                }
            }
        });
    }

    @Test void blockedRenewalDoesNotPreventAnotherOwnersRenewal() throws Exception {
        String appId = "owner-independent-" + UUID.randomUUID();
        var definitionId = new WorkflowDefinitionId("agentic-test", "owner-coordinator", "1.0.0");
        try (var app = WorkflowApplication.builder().withId(appId).disableLifeCycleCEPublishing()
                     .disableStatusChangePublishing().build();
             var coordinator = new WorkflowOwnerCoordinator(data, transactions, bindings, 2)) {
            var first = ownerFixture(app, definitionId);
            var second = ownerFixture(app, definitionId);
            var firstSession = coordinator.acquire(first.binding(), first.context(), Duration.ofMillis(1800)).orElseThrow();
            var secondSession = coordinator.acquire(second.binding(), second.context(), Duration.ofMillis(4500)).orElseThrow();
            try (var lock = data.getConnection()) {
                lock.setAutoCommit(false);
                try (var q = lock.prepareStatement("select id from task.instance where id=? for update")) {
                    q.setString(1, first.binding().instanceId().value()); q.executeQuery().close();
                }
                await(() -> countWaitingRegistryChecks() > 0, Duration.ofSeconds(3));
                await(() -> count("select count(*) from task.workflow_owner_lease where instance_id=? and renewed_at>acquired_at",
                        second.binding().instanceId().value()) == 1, Duration.ofMillis(1600));
                assertEquals(WorkflowOwnerCoordinator.State.ACTIVE, firstSession.state(), "first renewal is still blocked");
                assertEquals(WorkflowOwnerCoordinator.State.ACTIVE, secondSession.state());
                assertDoesNotThrow(() -> fence.requireCurrent(second.context()));
                lock.rollback();
            }
        }
    }

    private record OwnerFixture(JdbcWorkflowOwnerLease.Binding binding, WorkflowContextData context) { }

    private OwnerFixture ownerFixture(WorkflowApplication app, WorkflowDefinitionId definitionId) {
        String tenant = "tenant-" + UUID.randomUUID(), engine = "engine-" + UUID.randomUUID();
        var actor = new Subject(SubjectKind.USER, "owner-renewal-test");
        var definition = new DefinitionRef(definitionId.namespace() + "/" + definitionId.name(), definitionId.version());
        var request = new StartRequest(tenant, actor, definition, Map.of(), "owner-renewal-" + UUID.randomUUID());
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
        row = store.replace(row.successor(now, InstanceStatus.RUNNING, engine, 0, null, null), row.version(), "test.running", actor);
        var binding = new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), app.id(), engine, definition);
        var sdkInstance = (WorkflowInstanceData) Proxy.newProxyInstance(WorkflowInstanceData.class.getClassLoader(),
                new Class<?>[]{WorkflowInstanceData.class}, (p, m, a) -> m.getName().equals("id") ? engine : null);
        var sdkDefinition = (WorkflowDefinitionData) Proxy.newProxyInstance(WorkflowDefinitionData.class.getClassLoader(),
                new Class<?>[]{WorkflowDefinitionData.class}, (p, m, a) -> switch(m.getName()) {
                    case "id" -> definitionId; case "application" -> app; default -> null;
                });
        var context = (WorkflowContextData) Proxy.newProxyInstance(WorkflowContextData.class.getClassLoader(),
                new Class<?>[]{WorkflowContextData.class}, (p, m, a) -> switch(m.getName()) {
                    case "instanceData" -> sdkInstance; case "definition" -> sdkDefinition; default -> null;
                });
        return new OwnerFixture(binding, context);
    }

    private long countWaitingRegistryChecks() throws SQLException {
        try (var c = data.getConnection(); var q = c.prepareStatement("select count(*) from pg_stat_activity "
                + "where datname=current_database() and wait_event_type='Lock' "
                + "and query like 'select engine_instance_id,definition_id,definition_version,status from task.instance%'");
             var r = q.executeQuery()) { r.next(); return r.getLong(1); }
    }
    private long countWaitingLeaseChecks() throws SQLException {
        try (var c = data.getConnection(); var q = c.prepareStatement("select count(*) from pg_stat_activity "
                + "where datname=current_database() and wait_event_type='Lock' "
                + "and query like 'select tenant,application_id,engine_instance_id,%task.workflow_owner_lease%'");
             var r = q.executeQuery()) { r.next(); return r.getLong(1); }
    }
    private static boolean hasMessage(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (String.valueOf(cause.getMessage()).contains(text)) return true;
        return false;
    }

    private static void publish(Running running) throws Exception {
        running.broker().publish(CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                .withSource(URI.create("urn:owner:coordinator-test")).withType("io.openworkflow.owner.coordinator").build())
                .get(10, TimeUnit.SECONDS);
    }
    private long count(String sql, String id) throws SQLException {
        try (var c = data.getConnection(); var q = c.prepareStatement(sql)) {
            q.setString(1, id); try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    @FunctionalInterface private interface Condition { boolean test() throws Exception; }
    private static void await(Condition condition, Duration timeout) throws Exception {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) { if (condition.test()) return; Thread.sleep(50); }
        fail("timed out waiting for owner state");
    }
}
