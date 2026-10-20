package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.quarkiverse.flow.persistence.jpa.*;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Actual JPA proofs and original reader. Retained pre-finalizer rows model historical crash gaps, not OS process death. */
@QuarkusTest
class InboxCheckpointRecoveryTest {
    @Inject JpaInstanceWriter writer;
    @Inject JpaInstanceReader reader;
    @Inject JdbcEventInbox inbox;
    @Inject JdbcInboxDispatch dispatch;
    @Inject JdbcOwnedInbox owned;
    @Inject JdbcInboxTaskLease leases;
    @Inject JdbcInboxCheckpointProof proof;
    @Inject JdbcInboxCheckpointRecovery recovery;
    @Inject JdbcInstanceStore registry;
    @Inject RegistryScan scan;
    @Inject RepairStore repairs;
    @Inject EngineStatusSync sync;
    @Inject AuditTrail audit;
    @Inject DataSource dataSource;
    private static final DefinitionRef DEFINITION=new DefinitionRef("agentic/checkpoint-recovery-test","1.0.0");
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    record Listening(WorkflowContext workflow,TaskContext task) { }
    static class Broker extends InMemoryEvents {
        final BlockingQueue<Listening> listening=new LinkedBlockingQueue<>();
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder b,Consumer<CloudEvent> callback,WorkflowContext w,TaskContext t) {
            var result=super.register(b,callback,w,t);listening.add(new Listening(w,t));return result;
        }
    }
    private PersistenceInstanceWriter borrowed() {
        return (PersistenceInstanceWriter)Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),new Class<?>[]{PersistenceInstanceWriter.class},
                (p,m,args)-> {
                    if(m.getName().equals("close")) return null;
                    try { return m.invoke(writer,args); } catch(InvocationTargetException error) { throw error.getCause(); }
                });
    }
    private final class Fixture implements AutoCloseable {
        final String appId="receipt-repair-"+UUID.randomUUID();
        final Broker broker=new Broker();
        final PersistenceCommitGate gate=new PersistenceCommitGate(Duration.ofSeconds(15),Clock.systemUTC());
        final CompletableFuture<Void> next=new CompletableFuture<>(),releaseNext=new CompletableFuture<>();
        final List<InboxWait> waits=new ArrayList<>();final List<InboxMessage> messages=new ArrayList<>();
        final List<InboxTaskClaim> claims=new ArrayList<>();
        final WorkflowApplication application;
        final InstanceRecord row;
        Fixture(int received) throws Exception {
            application=WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    // Original supported checkpoint/proof path, deliberately without automatic ACK to retain real historical gaps.
                    .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(),gate,(op,id)->{})))
                    .withListener(new WorkflowExecutionCompletableListener() {
                        public CompletableFuture<?> onTaskStarted(TaskStartedEvent e) {
                            if(e.taskContext().taskName().equals("continueAfterRepair")) {next.complete(null);return releaseNext;}
                            return CompletableFuture.completedFuture(null);
                        }
                    }).build();
            var definition=application.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("event/checkpoint-recovery.yaml"));
            var instance=definition.instance(Map.of());
            var request=new StartRequest("receipt-repair-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),DEFINITION,Map.of(),UUID.randomUUID().toString());
            var created=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.insert").record();
            created=registry.replace(created.successor(now(),InstanceStatus.RUNNING,instance.id(),0,null,null),created.version(),"test.running",created.owner());
            row=registry.replace(created.successor(now(),InstanceStatus.WAITING,instance.id(),0,null,null),created.version(),"test.waiting",created.owner());
            instance.start();
            for(int i=0;i<Math.max(1,received);i++) {
                var listening=Objects.requireNonNull(broker.listening.poll(10,TimeUnit.SECONDS));var task=listening.task();
                var wait=new InboxWait(row.tenant(),row.id(),appId,instance.id(),"agentic","checkpoint-recovery-test","1.0.0",
                        task.position().jsonPointer(),task.iteration(),"orders","org.openworkflow.agentic.repair.test","a".repeat(64),Duration.ofMinutes(5));
                var message=new InboxMessage(row.tenant(),"urn:test:repair",UUID.randomUUID().toString(),wait.eventType(),row.id(),wait.route(),"{}");
                dispatch.register(wait);inbox.receive(message,now().plusSeconds(600));
                var claim=leases.acquire(wait.task(),"repair-owner-"+i,Duration.ofSeconds(30)).orElseThrow().claim();claims.add(claim);
                owned.match(claim,wait,message);var reserved=owned.reserve(claim,wait).orElseThrow();
                InboxCheckpointMarker.install(listening.workflow(),task,reserved,claim);waits.add(wait);messages.add(message);
                if(received==0) break;
                broker.publish(CloudEventBuilder.v1().withId(message.eventId()).withSource(URI.create(message.source())).withType(message.type())
                        .withData("application/json",message.dataJson().getBytes(StandardCharsets.UTF_8)).build())
                        .get(10,TimeUnit.SECONDS);
                assertTimeoutPreemptively(Duration.ofSeconds(5),()-> {while(!proof.consumed(wait,message)) Thread.sleep(5);});
            }
            if(received==2) next.get(10,TimeUnit.SECONDS);
        }
        public void close() {
            gate.close();releaseNext.complete(null);application.close();
            for(var claim:claims) leases.release(claim);
        }
    }
    private int acknowledged(InstanceRecord row) throws Exception {
        try(var c=dataSource.getConnection();var s=c.prepareStatement("select count(*) from inbox.delivery where tenant=? and instance_id=? and state='ACKNOWLEDGED'")) {
            s.setString(1,row.tenant());s.setString(2,row.id().value());try(var r=s.executeQuery()) {r.next();return r.getInt(1);}
        }
    }
    @Test void anUncheckpointedReservationIsNeverInventedAsConsumedOrAcknowledged() throws Exception {
        try(var f=new Fixture(0)) {
            var calls=new AtomicInteger();var service=new JdbcInboxCheckpointRecovery(dataSource,e->calls.incrementAndGet(),Clock.systemUTC());
            assertTrue(service.repairBatch(f.row,f.appId,1));assertEquals(0,calls.get());assertEquals(0,acknowledged(f.row));
            assertFalse(proof.consumed(f.waits.getFirst(),f.messages.getFirst()));assertFalse(f.next.isDone());
        }
    }
    @Test void actualRetainedProofsRepairInBoundedBatchesAndDoNotRepeatAcknowledgedAudits() throws Exception {
        try(var f=new Fixture(2)) {
            var calls=new AtomicInteger();var service=new JdbcInboxCheckpointRecovery(dataSource,e->{calls.incrementAndGet();audit.record(e);},Clock.systemUTC());
            assertFalse(service.repairBatch(f.row,f.appId,1));assertEquals(1,acknowledged(f.row));
            assertTrue(service.repairBatch(f.row,f.appId,1));assertEquals(2,acknowledged(f.row));assertEquals(2,calls.get());
            assertTrue(service.repairBatch(f.row,f.appId,1));assertEquals(2,calls.get());
        }
    }
    @Test void mismatchedApplicationTenantAndFullOwnerFailBeforeAuditOrAcknowledgement() throws Exception {
        try(var f=new Fixture(2)) {
            var calls=new AtomicInteger();var service=new JdbcInboxCheckpointRecovery(dataSource,e->calls.incrementAndGet(),Clock.systemUTC());
            assertThrows(IllegalStateException.class,()->service.repairBatch(f.row,"wrong-application",64));
            for(var changed:List.of(copy(f.row,"other-tenant",f.row.owner()),
                    copy(f.row,f.row.tenant(),new Subject(SubjectKind.SERVICE,f.row.owner().id()))))
                assertThrows(IllegalStateException.class,()->service.repairBatch(changed,f.appId,64));
            assertEquals(0,calls.get());assertEquals(0,acknowledged(f.row));
        }
    }
    private static InstanceRecord copy(InstanceRecord r,String tenant,Subject owner) {
        return new InstanceRecord(r.id(),tenant,owner,r.idempotencyKey(),r.requestFingerprint(),r.definition(),r.input(),r.status(),r.engineInstanceId(),
                r.startAttempts(),r.lastError(),r.waitDeadline(),r.retriedFrom(),r.version(),r.createdAt(),r.updatedAt());
    }
    @Test void auditFailurePreservesActualProofAndKeepsCheckpointHealthDownUntilRestart() throws Exception {
        try(var f=new Fixture(2);var health=new PersistenceCommitGate(Duration.ofSeconds(5),Clock.systemUTC())) {
            var failing=new JdbcInboxCheckpointRecovery(dataSource,e->{throw new IllegalStateException("audit unavailable");},Clock.systemUTC());
            var gate=new InboxRestoreCheckpointGate(failing,f.application,health);
            assertThrows(IllegalStateException.class,()->gate.prepare(f.row));assertFalse(health.status().healthy());assertEquals(0,acknowledged(f.row));
            assertTrue(proof.consumed(f.waits.getFirst(),f.messages.getFirst()));
            assertTrue(recovery.repairBatch(f.row,f.appId,64));assertEquals(2,acknowledged(f.row));
            assertThrows(StoreUnavailableException.class,()->new InboxRestoreCheckpointGate(recovery,f.application,health).prepare(f.row));
            try(var freshHealth=new PersistenceCommitGate(Duration.ofSeconds(5),Clock.systemUTC())) {
                assertTrue(new InboxRestoreCheckpointGate(recovery,f.application,freshHealth).prepare(f.row));assertTrue(freshHealth.status().healthy());
            }
        }
    }
    @Test void originalJpaReaderAndRealReconcilerStartOnlyAfterReceiptRepairAndDoNotRedeliverCompletedListens() throws Exception {
        try(var f=new Fixture(2);var freshHealth=new PersistenceCommitGate(Duration.ofSeconds(15),Clock.systemUTC())) {
            f.gate.close(); // Stop the old actor's writes. This is component restart, not an OS-kill/partition claim.
            var resumedNext=new CompletableFuture<Void>();var release=new CompletableFuture<Void>();var cleaned=new CompletableFuture<Void>();
            var restoredBroker=new Broker();
            try(var finalizer=new InboxCheckpointFinalizer(owned,leases);
                var app=WorkflowApplication.builder().withId(f.appId).withEventConsumer(restoredBroker)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(new CheckpointingWriter(borrowed(),freshHealth,(op,id)->{
                        if(op==PersistenceCommitGate.Operation.COMPLETED) cleaned.complete(null);
                    },finalizer)))
                    .withListener(new WorkflowExecutionCompletableListener() {
                        public CompletableFuture<?> onTaskStarted(TaskStartedEvent e) {
                            if(e.taskContext().taskName().equals("continueAfterRepair")) {
                                try {assertEquals(2,acknowledged(f.row));resumedNext.complete(null);}
                                catch(Throwable failure) {resumedNext.completeExceptionally(failure);}
                                return release;
                            }
                            return CompletableFuture.completedFuture(null);
                        }
                    }).build()) {
                var definition=app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("event/checkpoint-recovery.yaml"));
                var restored=reader.find(definition,f.row.engineInstanceId()).orElseThrow();
                assertEquals(WorkflowStatus.PENDING,restored.status());assertFalse(resumedNext.isDone());assertEquals(0,acknowledged(f.row));
                var settings=new LifecycleSettings(false,Duration.ofSeconds(30),Duration.ofSeconds(120),Duration.ofMillis(1),3,
                        Duration.ofMillis(1),Duration.ofMillis(1),Duration.ofHours(24),100,Optional.empty());
                var reconciler=new Reconciler(registry,new FlowWorkflowEngine(app),sync,scan,repairs,settings,dataSource,Clock.systemUTC(),
                        f.row.tenant()::equals,ResumeGate.ALLOW,new InboxRestoreCheckpointGate(recovery,app,freshHealth));
                reconciler.recover(f.row.tenant(),f.row.id().value());
                resumedNext.get(10,TimeUnit.SECONDS);assertEquals(2,acknowledged(f.row));
                assertTrue(restoredBroker.listening.isEmpty(),"original reader skips both actually checkpointed listens");
                release.complete(null);cleaned.get(10,TimeUnit.SECONDS);assertTrue(freshHealth.status().healthy());
            } finally {release.complete(null);}
        }
    }
}
