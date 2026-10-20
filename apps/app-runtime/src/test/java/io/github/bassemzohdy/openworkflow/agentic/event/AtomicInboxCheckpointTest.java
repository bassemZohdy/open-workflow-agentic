package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.quarkiverse.flow.persistence.jpa.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Actual pinned SDK + injected JPA writer + PostgreSQL. The broker is test transport, not a checkpoint double. */
@QuarkusTest
class AtomicInboxCheckpointTest {
    @Inject JpaInstanceWriter writer;
    @Inject JpaInstanceOperations operations;
    @Inject JdbcInboxCheckpointProof proof;
    @Inject JdbcInboxDispatch dispatch;
    @Inject JdbcEventInbox inbox;
    @Inject InstanceStore registry;
    @Inject EntityManager em;
    @Inject DataSource dataSource;
    @Inject JdbcInboxTaskLease taskLeases;

    record Listening(WorkflowContext workflow, TaskContext task) { }
    static class Broker extends InMemoryEvents {
        final CompletableFuture<Listening> listening = new CompletableFuture<>();
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder b, Consumer<CloudEvent> callback,
                                                       WorkflowContext workflow, TaskContext task) {
            var registration = super.register(b,callback,workflow,task);
            listening.complete(new Listening(workflow,task)); return registration;
        }
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private InstanceRecord move(InstanceRecord row, InstanceStatus status, String engine) {
        return registry.replace(row.successor(now(),status,engine,row.startAttempts(),null,null),row.version(),
                "test.atomic-checkpoint",row.owner());
    }
    private PersistenceInstanceWriter borrowedWriter() {
        // Closing this isolated test application must NOT close the injected runtime's shared persistence executor.
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class},(proxy,method,args)-> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(writer,args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    @Test void exactReceiptCommitsWithActualWaitAndAnotherComponentAcknowledgesWithoutRedelivery() throws Exception {
        execute(Mode.COMMIT);
    }
    @Test void newerDispatchAttemptFencesAndRollsBackTheOldSdkCheckpoint() throws Exception {
        execute(Mode.HANDOVER);
    }
    @Test void cancellationBeforeCheckpointRollsBackBothReceiptProofAndActualTaskCompletion() throws Exception {
        execute(Mode.CANCEL);
    }
    @Test void anotherTaskOwnerFencesTheOldSdkCheckpointEvenWhenItsDispatchAttemptIsUnchanged() throws Exception {
        execute(Mode.TASK_HANDOVER);
    }
    @Test void anExpiredTaskLeaseCannotCommitItsSdkCheckpointOrReceiptProof() throws Exception {
        execute(Mode.TASK_EXPIRED);
    }
    @Test void taskLeaseExpiryIsCheckedAgainAfterWaitingForTheDeliveryRowLock() throws Exception {
        execute(Mode.TASK_DELAYED_EXPIRED);
    }
    @Test void anInTimeReservationCanCheckpointAfterItsWaitDeadlineWithTheSameLiveOwner() throws Exception {
        execute(Mode.RESERVATION_WON_DEADLINE);
    }
    enum Mode { COMMIT, HANDOVER, CANCEL, TASK_HANDOVER, TASK_EXPIRED, TASK_DELAYED_EXPIRED, RESERVATION_WON_DEADLINE }

    private void execute(Mode mode) throws Exception {
        assertInstanceOf(InboxCheckpointJpaOperations.class,operations);
        try(var gate = new PersistenceCommitGate(Duration.ofSeconds(15),Clock.systemUTC()); var broker = new Broker()) {
            var next = new CompletableFuture<Void>(); var releaseNext = new CompletableFuture<Void>();
            var cleaned = new CompletableFuture<Void>();
            var guarded = new CheckpointingWriter(borrowedWriter(),gate,(op,id)-> {
                if (op == PersistenceCommitGate.Operation.COMPLETED) cleaned.complete(null);
            });
            try(var app = WorkflowApplication.builder().withId("atomic-"+UUID.randomUUID()).withEventConsumer(broker)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(guarded))
                    .withListener(new WorkflowExecutionCompletableListener() {
                        @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                            if (event.taskContext().taskName().equals("performEffect")) {
                                next.complete(null); return releaseNext;
                            }
                            return CompletableFuture.completedFuture(null);
                        }
                    }).build()) {
                var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("event/atomic-receipt.yaml"));
                var instance = definition.instance(Map.of());
                var request = new StartRequest("atomic-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),
                        new DefinitionRef("agentic/atomic-receipt-test","1.0.0"),Map.of(),"start");
                var row = registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
                row = move(move(row,InstanceStatus.RUNNING,instance.id()),InstanceStatus.WAITING,instance.id());
                if(mode==Mode.RESERVATION_WON_DEADLINE) {
                    row=registry.replace(row.successor(now(),InstanceStatus.WAITING,instance.id(),row.startAttempts(),null,now().plusSeconds(5)),
                            row.version(),"test.original-deadline",row.owner());
                }
                var completed = instance.start();
                var listening = broker.listening.get(10,TimeUnit.SECONDS);
                var task = listening.task();
                TaskInfoKey key = TaskInfoKey.from(listening.workflow(),task); // Capture the SDK identity before dispatch.
                var wait = new InboxWait(row.tenant(),row.id(),app.id(),instance.id(),"agentic","atomic-receipt-test","1.0.0",
                        task.position().jsonPointer(),task.iteration(),"orders","org.openworkflow.agentic.atomic.test",
                        "a".repeat(64),Duration.ofMinutes(5));
                var message = new InboxMessage(row.tenant(),"urn:test:atomic",UUID.randomUUID().toString(),wait.eventType(),
                        row.id(),wait.route(),"{\"ok\":true}");
                dispatch.register(wait); inbox.receive(message,now().plusSeconds(600));
                assertEquals(Optional.of(message),inbox.matchCandidate(wait,message));
                var claim = dispatch.claim(wait).orElseThrow();
                JdbcInboxDispatch.Claim newer = null;
                java.sql.Connection blocker=null;
                int blockerPid=0;
                try {
                    var reserved = dispatch.reserve(claim,proof).orElseThrow();
                    assertFalse(proof.consumed(wait,message));
                    var lease=taskLeases.acquire(wait.task(),"owner-"+UUID.randomUUID(),
                            mode==Mode.TASK_EXPIRED ? Duration.ofMillis(100) : mode==Mode.TASK_DELAYED_EXPIRED
                                    ? Duration.ofSeconds(5) : Duration.ofSeconds(30)).orElseThrow();
                    InboxCheckpointMarker.install(listening.workflow(),task,reserved,lease.claim());
                    if (mode == Mode.HANDOVER) {
                        claim.close(); newer = dispatch.claim(wait).orElseThrow();
                        assertEquals(2,dispatch.reserve(newer,proof).orElseThrow().attempt());
                    } else if (mode == Mode.CANCEL) {
                        move(row,InstanceStatus.CANCEL_REQUESTED,instance.id());
                    } else if (mode == Mode.TASK_HANDOVER) {
                        assertTrue(taskLeases.release(lease.claim()));
                        assertEquals(lease.claim().epoch()+1,taskLeases.acquire(wait.task(),"replacement-"+UUID.randomUUID(),
                                Duration.ofSeconds(30)).orElseThrow().claim().epoch());
                    } else if (mode == Mode.TASK_EXPIRED) {
                        assertTimeoutPreemptively(Duration.ofSeconds(5),()-> {
                            while(!databaseExpired(lease)) Thread.sleep(10);
                        });
                    } else if(mode==Mode.TASK_DELAYED_EXPIRED) {
                        blocker=dataSource.getConnection(); blocker.setAutoCommit(false);
                        try(var s=blocker.prepareStatement("select 1 from inbox.delivery where tenant=? and instance_id=? and wait_key=? for update")) {
                            s.setString(1,wait.tenant()); s.setString(2,wait.instanceId().value()); s.setString(3,wait.key());
                            try(var r=s.executeQuery()) { assertTrue(r.next()); }
                        }
                        try(var s=blocker.prepareStatement("select pg_backend_pid()");var r=s.executeQuery()) { r.next(); blockerPid=r.getInt(1); }
                    } else if(mode==Mode.RESERVATION_WON_DEADLINE) {
                        assertTimeoutPreemptively(Duration.ofSeconds(10),()-> {
                            while(!deliveryExpired(wait)) Thread.sleep(10);
                        });
                    }
                    broker.publish(CloudEventBuilder.v1().withId(message.eventId()).withSource(URI.create(message.source()))
                            .withType(message.type()).withData("application/json",message.dataJson().getBytes(StandardCharsets.UTF_8))
                            .build()).get(10,TimeUnit.SECONDS);
                    if(mode==Mode.TASK_DELAYED_EXPIRED) {
                        awaitDeliveryBlock(blockerPid);
                        assertFalse(databaseExpired(lease),"reach the actual row-lock wait while the initial lease is still valid");
                        assertTimeoutPreemptively(Duration.ofSeconds(10),()-> { while(!databaseExpired(lease)) Thread.sleep(10); });
                        blocker.commit(); blocker.close(); blocker=null;
                    }
                    if (mode == Mode.COMMIT || mode==Mode.RESERVATION_WON_DEADLINE) {
                        next.get(15,TimeUnit.SECONDS);
                        assertFalse(completed.isDone(),"observe the committed wait before SDK workflow-completion cleanup");
                        assertTrue(QuarkusTransaction.requiringNew().call(()->em.find(CompletedTaskEntity.class,key)!=null));
                        assertTrue(proof.consumed(wait,message),"actual task and receipt proof committed together");
                        releaseNext.complete(null);
                        completed.get(15,TimeUnit.SECONDS); cleaned.get(5,TimeUnit.SECONDS);
                        assertFalse(QuarkusTransaction.requiringNew().call(()->em.find(CompletedTaskEntity.class,key)!=null),
                                "the SDK removes completed workflow state; receipt proof must survive that cleanup");
                        assertTrue(proof.consumed(wait,message));
                        var another = new InboxMessage(message.tenant(),message.source(),message.eventId()+"-other",message.type(),
                                message.instanceId(),message.correlationKey(),message.dataJson());
                        assertFalse(proof.consumed(wait,another));
                        move(row,InstanceStatus.COMPLETED,instance.id());
                        claim.close(); var restarted = new JdbcInboxDispatch(dataSource,e->{});
                        try(var recovered = restarted.claim(wait).orElseThrow()) {
                            assertTrue(restarted.reserve(recovered,proof).isEmpty(),"checkpoint-before-ACK must not authorize replay");
                        }
                        try(var c=dataSource.getConnection(); var s=c.prepareStatement("select state from inbox.delivery "
                                + "where tenant=? and instance_id=? and wait_key=?")) {
                            s.setString(1,wait.tenant()); s.setString(2,wait.instanceId().value()); s.setString(3,wait.key());
                            try(var r=s.executeQuery()) { assertTrue(r.next()); assertEquals("ACKNOWLEDGED",r.getString(1)); }
                        }
                        assertImmutable(wait);
                    } else {
                        assertTimeoutPreemptively(Duration.ofSeconds(10),()-> {
                            while(gate.status().healthy()) Thread.sleep(10);
                        });
                        assertEquals(PersistenceCommitGate.Operation.TASK_COMPLETED,gate.status().failure().orElseThrow().operation());
                        assertFalse(proof.consumed(wait,message));
                        assertFalse(QuarkusTransaction.requiringNew().call(()->em.find(CompletedTaskEntity.class,key)!=null),
                                "receipt fence failure rolls back the real SDK checkpoint, not only our own row");
                        assertThrows(TimeoutException.class,()->next.get(150,TimeUnit.MILLISECONDS));
                        assertFalse(completed.isDone());
                    }
                } finally {
                    if(blocker!=null) { blocker.rollback(); blocker.close(); }
                    releaseNext.complete(null);
                    if (newer != null) newer.close(); claim.close();
                }
            }
        }
    }
    private boolean deliveryExpired(InboxWait wait) throws SQLException {
        try(var c=dataSource.getConnection();var s=c.prepareStatement("select deadline<=clock_timestamp() from inbox.delivery "
                +"where tenant=? and instance_id=? and wait_key=?")) {
            s.setString(1,wait.tenant()); s.setString(2,wait.instanceId().value()); s.setString(3,wait.key());
            try(var r=s.executeQuery()) { assertTrue(r.next()); return r.getBoolean(1); }
        }
    }
    private void awaitDeliveryBlock(int blockerPid) {
        assertTimeoutPreemptively(Duration.ofSeconds(5),()-> {
            while(true) {
                try(var c=dataSource.getConnection();var s=c.prepareStatement("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))")) {
                    s.setInt(1,blockerPid); try(var r=s.executeQuery()) { r.next(); if(r.getBoolean(1)) return; }
                }
                Thread.sleep(10);
            }
        });
    }
    private boolean databaseExpired(JdbcInboxTaskLease.Lease lease) throws SQLException {
        try(var c=dataSource.getConnection(); var s=c.prepareStatement("select expires_at<=clock_timestamp() from inbox.task_claim "
                +"where tenant=? and instance_id=? and task_digest=?")) {
            var task=lease.claim().task(); s.setString(1,task.tenant()); s.setString(2,task.instanceId().value()); s.setString(3,task.digest());
            try(var r=s.executeQuery()) { assertTrue(r.next()); return r.getBoolean(1); }
        }
    }
    private void assertImmutable(InboxWait wait) throws Exception {
        for(String sql:List.of("delete from inbox.checkpoint_receipt where tenant=? and instance_id=? and wait_key=?",
                "update inbox.checkpoint_receipt set dispatch_attempt=dispatch_attempt+1 where tenant=? and instance_id=? and wait_key=?")) {
            try(var c=dataSource.getConnection()) {
                c.setAutoCommit(false);
                try(var s=c.prepareStatement(sql)) {
                    s.setString(1,wait.tenant()); s.setString(2,wait.instanceId().value()); s.setString(3,wait.key());
                    assertThrows(SQLException.class,s::executeUpdate);
                } finally { c.rollback(); }
            }
        }
    }
}
