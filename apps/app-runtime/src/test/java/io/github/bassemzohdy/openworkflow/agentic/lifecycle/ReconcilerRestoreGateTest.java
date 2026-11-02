package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real registry/epoch transitions, with the existing explicit engine double; actual reader/SDK repair is tested separately. */
@QuarkusTest
class ReconcilerRestoreGateTest {
    @Inject JdbcInstanceStore store;
    @Inject RegistryScan scan;
    @Inject RepairStore repairs;
    @Inject EngineStatusSync sync;
    @Inject DataSource dataSource;
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private InstanceRecord fixture(InstanceStatus status,String engine) {
        var request=new StartRequest("restore-gate-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),FakeEngine.PROBE,
                Map.of(),UUID.randomUUID().toString());
        var row=store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.insert").record();
        row=store.replace(row.successor(now(),InstanceStatus.STARTING,engine,0,null,null),row.version(),"test.engine",row.owner());
        if(status!=InstanceStatus.STARTING) row=store.replace(row.successor(now(),InstanceStatus.RUNNING,engine,0,null,null),row.version(),"test.running",row.owner());
        if(status==InstanceStatus.WAITING) row=store.replace(row.successor(now(),status,engine,0,null,null),row.version(),"test.waiting",row.owner());
        return row;
    }
    private Reconciler reconciler(FakeEngine engine,InstanceRecord row,ResumeGate authority,RestoreCheckpointGate checkpoint) {
        var settings=new LifecycleSettings(false,Duration.ofSeconds(30),Duration.ofSeconds(120),Duration.ofMillis(1),3,
                Duration.ofMillis(1),Duration.ofMillis(1),Duration.ofHours(24),100,Optional.empty());
        return new Reconciler(store,engine,sync,scan,repairs,settings,dataSource,Clock.systemUTC(),row.tenant()::equals,authority,checkpoint);
    }
    private InstanceRecord unprepared() {
        var request=new StartRequest("starting-gate-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),FakeEngine.PROBE,
                Map.of(),UUID.randomUUID().toString());
        return store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.insert").record();
    }
    @Test void unpreparedStartRefusesWhenAuthorityIsUnavailableOrRevoked() {
        for(var outcome:List.of(ResumeGate.Outcome.UNAVAILABLE,ResumeGate.Outcome.REAUTHORIZATION_REQUIRED)) {
            var engine=new FakeEngine();var row=unprepared();var checkpointCalls=new AtomicInteger();
            var reconciler=reconciler(engine,row,r->new ResumeGate.Decision(outcome,"grant.unavailable"),
                    r->{checkpointCalls.incrementAndGet();return true;});
            reconciler.recover(row.tenant(),row.id().value());
            assertEquals(0,engine.prepares.get());assertEquals(0,engine.starts.get());assertEquals(0,checkpointCalls.get());
            var retained=store.find(row.tenant(),row.id()).orElseThrow();
            assertEquals(InstanceStatus.STARTING,retained.status());assertNull(retained.engineInstanceId());
        }
    }
    @Test void unpreparedStartRechecksAuthorityAfterEnginePreparationAndCanRecoverLater() {
        var engine=new FakeEngine();var row=unprepared();var revoked=new AtomicBoolean();
        engine.duringPrepare=()->revoked.set(true);
        ResumeGate authority=r->revoked.get()?new ResumeGate.Decision(ResumeGate.Outcome.REAUTHORIZATION_REQUIRED,"grant.revoked")
                :ResumeGate.Decision.proceed();
        var reconciler=reconciler(engine,row,authority,r->true);
        reconciler.recover(row.tenant(),row.id().value());
        assertEquals(1,engine.prepares.get());assertEquals(0,engine.starts.get());
        var pending=store.find(row.tenant(),row.id()).orElseThrow();
        assertEquals(InstanceStatus.STARTING,pending.status());assertNotNull(pending.engineInstanceId());
        revoked.set(false);engine.duringPrepare=null;
        reconciler.recover(row.tenant(),row.id().value());
        assertEquals(1,engine.prepares.get(),"a permitted recovery uses the already prepared instance");
        assertEquals(1,engine.restarts.get());
    }
    @Test void unpreparedStartCannotExecuteWhenCheckpointRepairFails() {
        var engine=new FakeEngine();var row=unprepared();
        var reconciler=reconciler(engine,row,ResumeGate.ALLOW,r->false);
        reconciler.recover(row.tenant(),row.id().value());
        assertEquals(1,engine.prepares.get());assertEquals(0,engine.starts.get());
        assertNotNull(store.find(row.tenant(),row.id()).orElseThrow().engineInstanceId());
    }

    @Test void unpreparedStartRechecksAuthorityAfterCheckpointRepair() {
        var engine=new FakeEngine();var row=unprepared();var revoked=new AtomicBoolean();
        ResumeGate authority=r->revoked.get()?new ResumeGate.Decision(ResumeGate.Outcome.REAUTHORIZATION_REQUIRED,"grant.revoked")
                :ResumeGate.Decision.proceed();
        var reconciler=reconciler(engine,row,authority,r->{revoked.set(true);return true;});
        reconciler.recover(row.tenant(),row.id().value());
        assertEquals(1,engine.prepares.get());assertEquals(0,engine.starts.get());
        assertEquals(InstanceStatus.STARTING,store.find(row.tenant(),row.id()).orElseThrow().status());
    }

    @Test void pendingRepairHoldsRestoredWorkWithoutIncrementingEpochThenACompleteBatchPermitsIt() {
        var engine=new FakeEngine();String id="resume-"+UUID.randomUUID();engine.report(id,EngineStatus.PENDING);
        var row=fixture(InstanceStatus.WAITING,id);var complete=new AtomicBoolean();
        var reconciler=reconciler(engine,row,ResumeGate.ALLOW,r->complete.get());
        reconciler.recover(row.tenant(),row.id().value());assertEquals(0,engine.restarts.get());
        assertEquals(0,scan.resumeEpoch(row.id().value()).orElseThrow());
        complete.set(true);reconciler.recover(row.tenant(),row.id().value());
        assertEquals(1,engine.restarts.get());assertEquals(1,scan.resumeEpoch(row.id().value()).orElseThrow());
    }
    @Test void receiptFailureCannotStartRestoredWorkOrClaimItsResumeEpoch() {
        var engine=new FakeEngine();String id="failed-"+UUID.randomUUID();engine.report(id,EngineStatus.PENDING);
        var row=fixture(InstanceStatus.RUNNING,id);
        var reconciler=reconciler(engine,row,ResumeGate.ALLOW,r->{throw new IllegalStateException("repair unavailable");});
        assertDoesNotThrow(()->reconciler.recover(row.tenant(),row.id().value()));
        assertEquals(0,engine.restarts.get());assertEquals(0,scan.resumeEpoch(row.id().value()).orElseThrow());
    }
    @Test void authorityLostWhileReceiptRepairBlocksIsCheckedAgainBeforeResume() {
        var engine=new FakeEngine();String id="revoked-"+UUID.randomUUID();engine.report(id,EngineStatus.PENDING);
        var row=fixture(InstanceStatus.RUNNING,id);var revoked=new AtomicBoolean();
        ResumeGate authority=r->revoked.get()?new ResumeGate.Decision(ResumeGate.Outcome.REAUTHORIZATION_REQUIRED,"grant.revoked")
                :ResumeGate.Decision.proceed();
        var reconciler=reconciler(engine,row,authority,r->{revoked.set(true);return true;});
        reconciler.recover(row.tenant(),row.id().value());
        assertEquals(0,engine.restarts.get());assertEquals(0,scan.resumeEpoch(row.id().value()).orElseThrow());
        assertEquals(InstanceStatus.WAITING,store.find(row.tenant(),row.id()).orElseThrow().status());
    }
    @Test void cancellationDuringRepairCannotReleaseTheRestoredInstance() {
        var engine=new FakeEngine();String id="cancelled-"+UUID.randomUUID();engine.report(id,EngineStatus.PENDING);
        var row=fixture(InstanceStatus.WAITING,id);
        var reconciler=reconciler(engine,row,ResumeGate.ALLOW,r->{
            store.replace(row.successor(now(),InstanceStatus.CANCEL_REQUESTED,id,0,null,null),row.version(),"test.cancel",row.owner());
            return true;
        });
        reconciler.recover(row.tenant(),row.id().value());
        assertEquals(0,engine.restarts.get());assertEquals(0,scan.resumeEpoch(row.id().value()).orElseThrow());
        assertEquals(InstanceStatus.CANCEL_REQUESTED,store.find(row.tenant(),row.id()).orElseThrow().status());
    }
    @Test void aPreparedPendingStartAlsoRunsTheGatesAndNeverMakesAnInvalidStartingToWaitingMove() {
        var engine=new FakeEngine();String id="initial-"+UUID.randomUUID();engine.report(id,EngineStatus.PENDING);
        var row=fixture(InstanceStatus.STARTING,id);var repairCalls=new AtomicInteger();
        var reconciler=reconciler(engine,row,r->new ResumeGate.Decision(ResumeGate.Outcome.REAUTHORIZATION_REQUIRED,"grant.expired"),
                r->{repairCalls.incrementAndGet();return true;});
        assertDoesNotThrow(()->reconciler.recover(row.tenant(),row.id().value()));
        assertEquals(0,engine.restarts.get());assertEquals(0,repairCalls.get());
        assertEquals(InstanceStatus.STARTING,store.find(row.tenant(),row.id()).orElseThrow().status());
    }
}
