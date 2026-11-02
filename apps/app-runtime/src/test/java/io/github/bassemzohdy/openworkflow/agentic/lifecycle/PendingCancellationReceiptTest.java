package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.quarkiverse.flow.persistence.jpa.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Real JPA writer/JTA tests in a disposable database; candidate is not installed in the application reader. */
@QuarkusTest
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PendingCancellationReceiptTest {
    private static final String DOCUMENT = """
        document: { dsl: '1.0.3', namespace: agentic-test, name: receipt-case, version: '1.0.0' }
        do:
          - wait:
              listen:
                to:
                  one:
                    with: { type: io.openworkflow.test.never }
        """;
    @Inject DataSource data;
    @Inject JpaInstanceWriter standardWriter;
    @Inject PendingCancellationReceiptWriter receiptWriter;
    @Inject JpaInstanceOperations operations;
    @Inject JdbcInstanceStore store;
    @Inject RegistryScan scan;
    @Inject RepairStore repairs;
    @Inject EngineStatusSync sync;

    @BeforeAll void applyProposal() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/proposed/V81__pending_cancellation_receipt.sql")) {
            assertNotNull(in);
            try (var c = data.getConnection(); var s = c.createStatement()) {
                s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
    PersistenceInstanceWriter borrowed(PersistenceInstanceWriter source) {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(source, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }
    interface Scenario { void run(WorkflowInstance instance, InstanceRecord row, String appId,
                                   PersistenceCommitGate gate, WorkflowEngine engine) throws Exception; }
    void withRestored(Scenario scenario) throws Exception {
        withRestored(java.util.function.UnaryOperator.identity(), scenario);
    }
    void withRestored(java.util.function.UnaryOperator<PersistenceInstanceWriter> wrap, Scenario scenario) throws Exception {
        withRestored(wrap, false, scenario);
    }
    void withRestored(java.util.function.UnaryOperator<PersistenceInstanceWriter> wrap, boolean observe,
                      Scenario scenario) throws Exception {
        String appId = "receipt-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var listening = new CountDownLatch(1);
        var broker = new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var result = super.register(builder, callback, workflow, task); listening.countDown(); return result;
            }
        };
        try (var firstGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC());
             var cancelGate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC());
             var first = WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(standardWriter), firstGate, (op,id)->{}))).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML);
            var original = first.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(listening.await(10, TimeUnit.SECONDS));
            var actor = new Subject(SubjectKind.USER, "receipt-user");
            var request = new StartRequest(tenant, actor, new DefinitionRef("agentic-test/receipt-case", "1.0.0"),
                    Map.of(), "receipt-" + UUID.randomUUID());
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
            row = store.replace(row.successor(now, InstanceStatus.RUNNING, original.id(), 0, null, null), row.version(), "test.running", actor);
            row = store.replace(row.successor(now, InstanceStatus.WAITING, original.id(), 0, null, now.plusSeconds(60)), row.version(), "test.waiting", actor);
            row = store.replace(row.successor(now, InstanceStatus.CANCEL_REQUESTED, original.id(), 0, null, null), row.version(), "test.cancel", actor);
            var builder = WorkflowApplication.builder().withId(appId)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing();
            if (observe) builder.withListener(new FlowEngineObserver(sync));
            try (var restored = builder.build()) {
                var definition = restored.workflowDefinition(workflow);
                var guardedWriter = new CheckpointingWriter(borrowed(receiptWriter), cancelGate, (op,id)->{});
                var reader = new CancellablePersistenceReader(operations, wrap.apply(guardedWriter));
                var instance = reader.find(definition, original.id()).orElseThrow();
                scenario.run(instance, row, appId, cancelGate, new FlowWorkflowEngine(restored));
            }
        }
    }
    @Test void retryAfterLostCommitAcknowledgementReusesExactReceipt() throws Exception {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        withRestored(real -> (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(real, args);
                        if (method.getName().equals("aborted") && first.getAndSet(false)) {
                            @SuppressWarnings("unchecked") var committed = (CompletableFuture<Void>) result;
                            return committed.thenCompose(ignored -> CompletableFuture.failedFuture(new IllegalStateException("lost acknowledgement")));
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                }), (instance, row, appId, gate, engine) -> {
            assertThrows(ExecutionException.class, () -> instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            assertEquals(0, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
            assertThrows(CancellationException.class, () -> instance.start().join());
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(WorkflowStatus.CANCELLED, instance.status());
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
        });
    }
    @Test void retryAfterPreCommitFailureKeepsTheStartGuard() throws Exception {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        withRestored(real -> (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("aborted") && first.getAndSet(false))
                        return CompletableFuture.failedFuture(new IllegalStateException("temporary failure"));
                    try { return method.invoke(real, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                }), (instance, row, appId, gate, engine) -> {
            assertThrows(ExecutionException.class, () -> instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(0, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            assertEquals(1, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
            assertThrows(CancellationException.class, () -> instance.start().join());
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(WorkflowStatus.CANCELLED, instance.status());
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
        });
    }
    @Test void engineCanReissueCancellationOfAnActiveGuardedInstance() throws Exception {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        withRestored(real -> (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("aborted") && first.getAndSet(false))
                        return CompletableFuture.failedFuture(new IllegalStateException("temporary failure"));
                    try { return method.invoke(real, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                }), (instance, row, appId, gate, engine) -> {
            assertEquals(EngineStatus.PENDING, engine.activeInstance(row.definition(), instance.id()).orElseThrow().status());
            assertFalse(engine.cancel(row.definition(), instance.id()));
            assertEquals(EngineStatus.PENDING, engine.activeInstance(row.definition(), instance.id()).orElseThrow().status());
            assertThrows(CancellationException.class, () -> instance.start().join());
            assertTrue(engine.cancel(row.definition(), instance.id()));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (engine.activeInstance(row.definition(), instance.id()).isPresent()) Thread.sleep(10);
            });
            assertEquals(WorkflowStatus.CANCELLED, instance.status());
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            assertEquals(0, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
        });
    }
    @Test void reconcilerReissuesFailedCancellationAndObserverConfirmsIt() throws Exception {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        withRestored(real -> (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("aborted") && first.getAndSet(false))
                        return CompletableFuture.failedFuture(new IllegalStateException("temporary failure"));
                    try { return method.invoke(real, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                }), true, (instance, row, appId, gate, engine) -> {
            var settings = new LifecycleSettings(false, Duration.ofSeconds(30), Duration.ofSeconds(120),
                    Duration.ofSeconds(1), 3, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    Duration.ofHours(24), 100, Optional.empty());
            var reconciler = new Reconciler(store, engine, sync, scan, repairs, settings, data,
                    Clock.systemUTC(), row.tenant()::equals);
            assertEquals(1, reconciler.recover(row.tenant(), row.id().value()).count("recover.examined"));
            assertEquals(InstanceStatus.CANCEL_REQUESTED, store.find(row.tenant(), row.id()).orElseThrow().status());
            assertEquals(1, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
            assertThrows(CancellationException.class, () -> instance.start().join());
            assertEquals(1, reconciler.recover(row.tenant(), row.id().value()).count("recover.examined"));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (store.find(row.tenant(), row.id()).orElseThrow().status() != InstanceStatus.CANCELLED)
                    Thread.sleep(10);
            });
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            assertEquals(0, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
        });
    }
    long count(String sql, String value) throws Exception {
        try (var c = data.getConnection(); var q = c.prepareStatement(sql)) {
            q.setString(1, value); try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    @Test void committedReceiptRecoversOnlyItsExactPendingCancellationOnce() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            var recovery = new PendingCancellationReceiptRecovery(data, store, Clock.systemUTC());
            assertFalse(recovery.recover(row.tenant(), row.id(), appId), "a request alone is not a receipt");
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(InstanceStatus.CANCEL_REQUESTED, store.find(row.tenant(), row.id()).orElseThrow().status(),
                    "without an observer the committed receipt must be needed");
            assertFalse(recovery.recover(row.tenant(), row.id(), appId + "-other"));
            assertFalse(recovery.recover("other-tenant", row.id(), appId));
            var workers = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var a = workers.submit(() -> { start.await(); return recovery.recover(row.tenant(), row.id(), appId); });
                var b = workers.submit(() -> { start.await(); return recovery.recover(row.tenant(), row.id(), appId); });
                start.countDown();
                assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS),
                        "only one recovery may advance the registry");
            } finally { workers.shutdownNow(); }
            assertEquals(InstanceStatus.CANCELLED, store.find(row.tenant(), row.id()).orElseThrow().status());
            assertFalse(recovery.recover(row.tenant(), row.id(), appId));
            assertEquals(1, count("select count(*) from task.instance_event where instance_id=? "
                    + "and reason='engine.receipt.CANCELLED'", row.id().value()));
        });
    }

    @Test void committedReceiptDoesNotOverrideACompetingTerminalOutcome() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            var current = store.find(row.tenant(), row.id()).orElseThrow();
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            store.replace(current.successor(now, InstanceStatus.FAILED, current.engineInstanceId(),
                    current.startAttempts(), null, null), current.version(), "test.competing-failure", current.owner());
            var recovery = new PendingCancellationReceiptRecovery(data, store, Clock.systemUTC());
            assertFalse(recovery.recover(row.tenant(), row.id(), appId));
            assertEquals(InstanceStatus.FAILED, store.find(row.tenant(), row.id()).orElseThrow().status());
        });
    }

    @Test void actualJpaDeleteAndExactReceiptCommitTogether() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            assertEquals(0, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            try (var c = data.getConnection(); var q = c.prepareStatement("""
                    select application_id,engine_instance_id,definition_id,definition_version
                    from task.pending_cancellation_receipt where instance_id=?
                    """)) {
                q.setString(1, row.id().value()); try (var r = q.executeQuery()) {
                    assertTrue(r.next()); assertEquals(appId, r.getString(1)); assertEquals(instance.id(), r.getString(2));
                    assertEquals("agentic-test/receipt-case", r.getString(3)); assertEquals("1.0.0", r.getString(4));
                }
            }
            assertThrows(CancellationException.class, () -> instance.start().join());
            for (String sql : List.of("update task.pending_cancellation_receipt set recorded_at=clock_timestamp() where instance_id=?",
                    "delete from task.pending_cancellation_receipt where instance_id=?")) {
                try (var c = data.getConnection(); var q = c.prepareStatement(sql)) {
                    q.setString(1, row.id().value()); assertThrows(SQLException.class, q::executeUpdate);
                }
            }
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
        });
    }
    @Test void receiptCannotCommitWithoutRemovingTheMatchingCheckpoint() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            try (var c = data.getConnection()) {
                c.setAutoCommit(false);
                try (var q = c.prepareStatement("""
                        insert into task.pending_cancellation_receipt
                        (instance_id,application_id,engine_instance_id,definition_id,definition_version)
                        values (?,?,?,?,?)
                        """)) {
                    q.setString(1, row.id().value()); q.setString(2, appId); q.setString(3, instance.id());
                    q.setString(4, "agentic-test/receipt-case"); q.setString(5, "1.0.0"); q.executeUpdate();
                    assertThrows(SQLException.class, c::commit); c.rollback();
                }
            }
            assertEquals(0, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            assertEquals(1, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
        });
    }
    @Test void failedJpaDeletionRollsBackTheReceiptAndDoesNotConfirmCancellation() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            assertTrue(appId.matches("receipt-[0-9a-f-]{36}"));
            try (var c = data.getConnection(); var s = c.createStatement()) {
                s.execute("create function task.test_reject_receipt_delete() returns trigger language plpgsql as $$ "
                        + "begin raise exception 'fixture deletion failure'; end $$");
                s.execute("create trigger test_reject_receipt_delete before delete on public.workflowinstanceentity "
                        + "for each row when (old.applicationid = '" + appId + "') "
                        + "execute function task.test_reject_receipt_delete()");
            }
            try {
                var pending = instance.cancelFuture();
                assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                    while (gate.status().failure().isEmpty()) Thread.sleep(10);
                });
                assertFalse(pending.isDone());
                assertEquals(WorkflowStatus.PENDING, instance.status());
                assertEquals(0, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
                assertEquals(1, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
                assertThrows(CancellationException.class, () -> instance.start().join());
            } finally {
                try (var c = data.getConnection(); var s = c.createStatement()) {
                    s.execute("drop trigger test_reject_receipt_delete on public.workflowinstanceentity");
                    s.execute("drop function task.test_reject_receipt_delete()");
                }
            }
        });
    }

    private record SdkSnapshot(byte[] input, Timestamp started, short status, String name, String namespace, String version) { }
    private SdkSnapshot snapshot(String appId, String engineId) throws Exception {
        try (var c = data.getConnection(); var q = c.prepareStatement("""
                select input,startedat,status,workflowname,workflownamespace,workflowversion
                from public.workflowinstanceentity where applicationid=? and instanceid=?
                """)) {
            q.setString(1, appId); q.setString(2, engineId);
            try (var r = q.executeQuery()) {
                assertTrue(r.next()); return new SdkSnapshot(r.getBytes(1), r.getTimestamp(2), r.getShort(3),
                        r.getString(4), r.getString(5), r.getString(6));
            }
        }
    }
    private void insertSdkSnapshot(Connection c, String appId, String engineId, SdkSnapshot snapshot) throws SQLException {
        try (var q = c.prepareStatement("""
                insert into public.workflowinstanceentity
                    (applicationid,instanceid,input,startedat,status,workflowname,workflownamespace,workflowversion)
                values (?,?,?,?,?,?,?,?)
                """)) {
            q.setQueryTimeout(5); q.setString(1, appId); q.setString(2, engineId); q.setBytes(3, snapshot.input());
            q.setTimestamp(4, snapshot.started()); q.setShort(5, snapshot.status()); q.setString(6, snapshot.name());
            q.setString(7, snapshot.namespace()); q.setString(8, snapshot.version()); q.executeUpdate();
        }
    }

    @Test void committedReceiptFencesStaleSdkWorkflowAndTaskWrites() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            var old = snapshot(appId, instance.id());
            assertTrue(instance.cancelFuture().get(10, TimeUnit.SECONDS));
            try (var c = data.getConnection()) {
                var failure = assertThrows(SQLException.class, () -> insertSdkSnapshot(c, appId, instance.id(), old));
                assertEquals("P0001", failure.getSQLState());
            }
            try (var c = data.getConnection(); var q = c.prepareStatement("""
                    insert into public.taskinfoentity(task_type,applicationid,iteration,jsonpointer,workflowinstanceid)
                    values (0,?,0,'do/0/stale',?)
                    """)) {
                q.setString(1, appId); q.setString(2, instance.id());
                var failure = assertThrows(SQLException.class, q::executeUpdate);
                assertEquals("P0001", failure.getSQLState());
            }
            assertEquals(0, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
        });
    }

    @Test void staleSdkInsertWaitingBehindCancellationSeesTheCommittedReceipt() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            var old = snapshot(appId, instance.id());
            var pool = Executors.newSingleThreadExecutor();
            var started = new CountDownLatch(1);
            try (var transaction = data.getConnection()) {
                transaction.setAutoCommit(false);
                try {
                    try (var q = transaction.prepareStatement("""
                            insert into task.pending_cancellation_receipt
                                (instance_id,application_id,engine_instance_id,definition_id,definition_version)
                            values (?,?,?,?,?)
                            """)) {
                        q.setString(1, row.id().value()); q.setString(2, appId); q.setString(3, instance.id());
                        q.setString(4, "agentic-test/receipt-case"); q.setString(5, "1.0.0"); q.executeUpdate();
                    }
                    for (String table : List.of("taskinfoentity", "workflowmetadataentity")) {
                        try (var q = transaction.prepareStatement("delete from public." + table + " where applicationid=? and workflowinstanceid=?")) {
                            q.setString(1, appId); q.setString(2, instance.id()); q.executeUpdate();
                        }
                    }
                    try (var q = transaction.prepareStatement("delete from public.workflowinstanceentity where applicationid=? and instanceid=?")) {
                        q.setString(1, appId); q.setString(2, instance.id()); assertEquals(1, q.executeUpdate());
                    }
                    var stale = pool.submit(() -> {
                        try (var c = data.getConnection()) {
                            started.countDown(); insertSdkSnapshot(c, appId, instance.id(), old); return "accepted";
                        } catch (SQLException expected) { return expected.getSQLState(); }
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> stale.get(300, TimeUnit.MILLISECONDS));
                    transaction.commit();
                    assertEquals("P0001", stale.get(10, TimeUnit.SECONDS));
                } finally { if (!transaction.getAutoCommit()) transaction.rollback(); }
            } finally { pool.shutdownNow(); }
            assertEquals(1, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            assertEquals(0, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
        });
    }

    @Test void mismatchCannotCreateAReceipt() throws Exception {
        withRestored((instance, row, appId, gate, engine) -> {
            try (var c = data.getConnection(); var q = c.prepareStatement("""
                    insert into task.pending_cancellation_receipt
                    (instance_id,application_id,engine_instance_id,definition_id,definition_version)
                    values (?,?,?,?,?)
                    """)) {
                q.setString(1, row.id().value()); q.setString(2, appId + "-different"); q.setString(3, instance.id());
                q.setString(4, "agentic-test/receipt-case"); q.setString(5, "1.0.0");
                assertThrows(SQLException.class, q::executeUpdate);
            }
            assertEquals(0, count("select count(*) from task.pending_cancellation_receipt where instance_id=?", row.id().value()));
            assertEquals(1, count("select count(*) from workflowinstanceentity where instanceid=?", instance.id()));
        });
    }
}
