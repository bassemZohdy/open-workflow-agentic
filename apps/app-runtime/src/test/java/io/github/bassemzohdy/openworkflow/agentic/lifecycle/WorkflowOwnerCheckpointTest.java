package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CancellationAwareJpaOperations;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Actual SDK context and JPA status write under the proposed owner fence; no production writer wiring. */
@QuarkusTest
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowOwnerCheckpointTest {
    private static final String DOCUMENT = """
        document: { dsl: '1.0.3', namespace: agentic-test, name: owner-checkpoint, version: '1.0.0' }
        do:
          - wait:
              listen:
                to:
                  one:
                    with: { type: io.openworkflow.test.never }
        """;
    @Inject DataSource data;
    @Inject JdbcInstanceStore store;
    @Inject JpaInstanceWriter writer;
    @Inject JpaInstanceOperations operations;
    @Inject EntityManager entities;
    @Inject WorkflowOwnerJpaFence fence;
    @Inject CancellationAwareJpaOperations guardedOperations;

    @BeforeAll void installProposal() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/proposed/V82__workflow_owner_lease.sql")) {
            assertNotNull(in);
            try (var c = data.getConnection(); var s = c.createStatement()) {
                s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test void actualJpaWriteRollsBackBeforeAnExpiredOwnerCanBeReplaced() throws Exception {
        String appId = "owner-jpa-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var captured = new AtomicReference<WorkflowContextData>();
        var listening = new CountDownLatch(1);
        var broker = new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var result = super.register(builder, callback, workflow, task); listening.countDown(); return result;
            }
        };
        PersistenceInstanceWriter capture = (PersistenceInstanceWriter) Proxy.newProxyInstance(
                PersistenceInstanceWriter.class.getClassLoader(), new Class<?>[]{PersistenceInstanceWriter.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("started")) captured.set((WorkflowContextData) args[0]);
                    try { return method.invoke(writer, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        try (broker; var app = WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowPersistenceListener(capture)).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML);
            var instance = app.workflowDefinition(workflow).instance(Map.of()); instance.start();
            assertTrue(listening.await(10, TimeUnit.SECONDS));
            var context = captured.get(); assertNotNull(context);
            var actor = new Subject(SubjectKind.USER, "owner-jpa-test");
            var definition = new DefinitionRef("agentic-test/owner-checkpoint", "1.0.0");
            var request = new StartRequest(tenant, actor, definition, Map.of(), "owner-jpa-" + UUID.randomUUID());
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
            row = store.replace(row.successor(now, InstanceStatus.RUNNING, instance.id(), 0, null, null), row.version(), "test.running", actor);
            var binding = new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), appId, instance.id(), definition);
            var leases = new JdbcWorkflowOwnerLease(data);
            var orderLease = leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(10)).orElseThrow();
            assertCancellationLockOrder(context, binding, orderLease.claim());
            assertTrue(leases.release(orderLease.claim()));
            var old = leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(3)).orElseThrow();
            assertThrows(RuntimeException.class, () -> fence.requireCurrent(context, old.claim()),
                    "a fence cannot run without the SDK writer transaction");
            var wrongBinding = new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), appId + "-other", instance.id(), definition);
            var wrongClaim = new JdbcWorkflowOwnerLease.Claim(wrongBinding, old.claim().ownerId(), old.claim().epoch());
            assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> fence.requireCurrent(context, wrongClaim)),
                    "a valid owner generation cannot be attached to a different SDK application");
            var written = new CountDownLatch(1); var proceed = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var oldWrite = workers.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                    fence.requireCurrent(context, old.claim());
                    operations.writeStatus(context, WorkflowStatus.SUSPENDED);
                    entities.flush();
                    written.countDown();
                    try { if (!proceed.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release writer"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    fence.requireCurrent(context, old.claim());
                }));
                assertTrue(written.await(8, TimeUnit.SECONDS), "actual JPA update must occur before expiry");
                Thread.sleep(3250);
                var starting = new CountDownLatch(1);
                var next = workers.submit(() -> { starting.countDown(); return leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(10)); });
                assertTrue(starting.await(5, TimeUnit.SECONDS));
                Thread.sleep(200);
                assertFalse(next.isDone(), "new owner cannot pass the uncommitted checkpoint transaction");
                proceed.countDown();
                assertThrows(ExecutionException.class, () -> oldWrite.get(8, TimeUnit.SECONDS));
                var successor = next.get(8, TimeUnit.SECONDS).orElseThrow();
                assertEquals(old.claim().epoch() + 1, successor.claim().epoch());
                assertEquals(1, countNullStatus(appId, instance.id()), "expired owner's flushed JPA write must roll back");
                assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> fence.requireCurrent(context, old.claim())));
                QuarkusTransaction.requiringNew().run(() -> {
                    fence.requireCurrent(context, successor.claim());
                    operations.writeStatus(context, WorkflowStatus.SUSPENDED);
                    entities.flush();
                    fence.requireCurrent(context, successor.claim());
                });
                assertEquals(0, countNullStatus(appId, instance.id()), "current owner's JPA write commits");
            } finally { proceed.countDown(); workers.shutdownNow(); }
        }
    }

    private void assertCancellationLockOrder(WorkflowContextData context, JdbcWorkflowOwnerLease.Binding binding,
                                             JdbcWorkflowOwnerLease.Claim claim) throws Exception {
        var advisoryHeld = new CountDownLatch(1); var contenderStarting = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var cancellation = workers.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                // This is the first lock taken by the real cancellation-aware SDK writer.
                guardedOperations.lockForOwnerFence(context);
                advisoryHeld.countDown();
                try {
                    if (!contenderStarting.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("contender did not start");
                    Thread.sleep(300);
                    try (var c = data.getConnection(); var q = c.prepareStatement("select id from task.instance where id=? for update")) {
                        q.setString(1, binding.instanceId().value()); q.executeQuery().close();
                    }
                } catch (SQLException e) { throw new IllegalStateException(e); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(advisoryHeld.await(5, TimeUnit.SECONDS));
            var checkpoint = workers.submit(() -> {
                contenderStarting.countDown();
                QuarkusTransaction.requiringNew().run(() -> fence.requireCurrent(context, claim));
            });
            cancellation.get(8, TimeUnit.SECONDS);
            checkpoint.get(8, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }

    private long countNullStatus(String appId, String id) throws SQLException {
        try (var c = data.getConnection(); var q = c.prepareStatement("select count(*) from public.workflowinstanceentity "
                + "where applicationid=? and instanceid=? and status is null")) {
            q.setString(1, appId); q.setString(2, id);
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
}
