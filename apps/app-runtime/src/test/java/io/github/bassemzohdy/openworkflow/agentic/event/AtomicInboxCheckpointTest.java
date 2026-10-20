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
    enum Mode { COMMIT, HANDOVER, CANCEL }

    private void execute(Mode mode) throws Exception {
        assertInstanceOf(InboxCheckpointJpaOperations.class,operations);
        try(var gate = new PersistenceCommitGate(Duration.ofSeconds(15),Clock.systemUTC()); var broker = new Broker()) {
            var next = new CompletableFuture<Void>();
            var guarded = new CheckpointingWriter(borrowedWriter(),gate,(op,id)->{});
            try(var app = WorkflowApplication.builder().withId("atomic-"+UUID.randomUUID()).withEventConsumer(broker)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(guarded))
                    .withListener(new WorkflowExecutionListener() {
                        @Override public void onTaskStarted(TaskStartedEvent event) {
                            if (event.taskContext().taskName().equals("performEffect")) next.complete(null);
                        }
                    }).build()) {
                var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("event/atomic-receipt.yaml"));
                var instance = definition.instance(Map.of());
                var request = new StartRequest("atomic-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),
                        new DefinitionRef("agentic/atomic-receipt-test","1.0.0"),Map.of(),"start");
                var row = registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
                row = move(move(row,InstanceStatus.RUNNING,instance.id()),InstanceStatus.WAITING,instance.id());
                var completed = instance.start();
                var listening = broker.listening.get(10,TimeUnit.SECONDS);
                var task = listening.task();
                var wait = new InboxWait(row.tenant(),row.id(),app.id(),instance.id(),"agentic","atomic-receipt-test","1.0.0",
                        task.position().jsonPointer(),task.iteration(),"orders","org.openworkflow.agentic.atomic.test",
                        "a".repeat(64),Duration.ofMinutes(5));
                var message = new InboxMessage(row.tenant(),"urn:test:atomic",UUID.randomUUID().toString(),wait.eventType(),
                        row.id(),wait.route(),"{\"ok\":true}");
                dispatch.register(wait); inbox.receive(message,now().plusSeconds(600));
                assertEquals(Optional.of(message),inbox.matchCandidate(wait,message));
                var claim = dispatch.claim(wait).orElseThrow();
                JdbcInboxDispatch.Claim newer = null;
                try {
                    var reserved = dispatch.reserve(claim,proof).orElseThrow();
                    assertFalse(proof.consumed(wait,message));
                    InboxCheckpointMarker.install(listening.workflow(),task,reserved);
                    if (mode == Mode.HANDOVER) {
                        claim.close(); newer = dispatch.claim(wait).orElseThrow();
                        assertEquals(2,dispatch.reserve(newer,proof).orElseThrow().attempt());
                    } else if (mode == Mode.CANCEL) {
                        move(row,InstanceStatus.CANCEL_REQUESTED,instance.id());
                    }
                    broker.publish(CloudEventBuilder.v1().withId(message.eventId()).withSource(URI.create(message.source()))
                            .withType(message.type()).withData("application/json",message.dataJson().getBytes(StandardCharsets.UTF_8))
                            .build()).get(10,TimeUnit.SECONDS);
                    TaskInfoKey key = TaskInfoKey.from(listening.workflow(),task);
                    if (mode == Mode.COMMIT) {
                        completed.get(15,TimeUnit.SECONDS); next.get(5,TimeUnit.SECONDS);
                        assertTrue(QuarkusTransaction.requiringNew().call(()->em.find(CompletedTaskEntity.class,key)!=null));
                        assertTrue(proof.consumed(wait,message),"actual task and receipt proof committed together");
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
                    if (newer != null) newer.close(); claim.close();
                }
            }
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
