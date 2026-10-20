package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;

/** Actual owned PostgreSQL ledger/queue/boundary tests. Central append+binding are TWO DATA-fixture commits,
 * not an atomic appendOnce consumer/chain-authenticity proof. Targets and private fences are protocol spies,
 * not actual HTTP/provider/signed-identity/PDP/task/lease producers. No product activation/acceptance claim. */
@QuarkusTest @TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class AuditedEffectsStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    EffectAuditTestDatabase db;
    JdbcInstanceStore registry;
    EffectAuditProducer producer;
    JdbcEffectLedger ledger;
    static final Subject OWNER=new Subject(SubjectKind.USER,"alice-𝄞");
    record Fixture(EffectScope scope,EffectCommand command) {
        EffectInvocation invocation(){return new EffectInvocation(scope,command,null);}
    }
    @BeforeAll void createOwnedDatabase() throws Exception {
        db=EffectAuditTestDatabase.open();registry=new JdbcInstanceStore(db.dataSource);producer=new EffectAuditProducer();
        ledger=new JdbcEffectLedger(db.dataSource,manager,producer);
    }
    @AfterAll void dropOnlyOwnedDatabase() throws Exception {if(db!=null)db.close();}
    Fixture fixture() {
        var req=new StartRequest("audited-effect-"+UUID.randomUUID(),OWNER,new DefinitionRef("agentic/audited-effect-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),req,Instant.now()),"test.start").record();
        registry.replace(row.successor(Instant.now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",OWNER);
        var scope=new EffectScope(row.tenant(),row.id(),OWNER);
        return new Fixture(scope,scope.command("charge","item/1","approved-charge","amount=10"));
    }
    void end(Fixture f) {
        var row=registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow();
        registry.replace(row.successor(Instant.now(),InstanceStatus.FAILED,row.engineInstanceId(),1,"test-ended",null),row.version(),"test.failed",OWNER);
    }
    void expire(Fixture f) {
        var row=registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow();
        registry.replace(row.successor(Instant.now(),row.status(),row.engineInstanceId(),1,null,Instant.now().minusSeconds(1)),row.version(),"test.deadline",OWNER);
    }
    static EffectTelemetry telemetry(){return new EffectTelemetry(OpenTelemetry.noop());}
    AuditedEffects gate(IdempotentAuditSink sink){return new AuditedEffects(db.dataSource,manager,sink,telemetry());}
    EffectExecutionFence current(){return (i,b)->{};} // Explicit protocol spy, never production identity evidence.
    List<EffectAuditProducer.Retained> history(EffectKey key) {
        return Transactions.inTransaction(db.dataSource,c->producer.forKey(c,key,0,128));
    }
    Effect effect(Fixture f){return ledger.find(f.command().key()).orElseThrow();}
    int pending(EffectKey key) {
        return Transactions.inTransaction(db.dataSource,c->{
            try(var q=c.prepareStatement("select count(*) from effect.audit_delivery where thread_name=? and node_id=? and idempotency_key=? and ack_source_key is null")) {
                q.setString(1,key.threadId().value());q.setString(2,key.nodeId());q.setString(3,key.idempotencyKey());
                try(var r=q.executeQuery()){assertTrue(r.next());return r.getInt(1);}
            }
        });
    }
    static final class Target implements EffectTarget {
        final String id;
        final AtomicInteger sends=new AtomicInteger(),reads=new AtomicInteger();
        Duration window=Duration.ZERO;
        Supplier<Reply> send=()->new Reply(Outcome.CONFIRMED,null),read=()->new Reply(Outcome.UNKNOWN,null);
        Target(String id){this.id=id;}
        @Override public String target(){return id;}
        @Override public Duration replayWindow(){return window;}
        @Override public Reply execute(String key){sends.incrementAndGet();return send.get();}
        @Override public Reply lookup(String key){reads.incrementAndGet();return read.get();}
    }
    /** DATA fixture readback, not a consumer implementation. The real AuditTrail append commits first,
     * and a SEPARATE insert commits V75 binding. It cannot establish atomic/idempotent appendOnce semantics. */
    AuditReceipt fixtureReceipt(AuditSource source,AuditEvent draft) {
        try(var c=db.dataSource.getConnection()) {
            try(var q=c.prepareStatement("select event_id,chain_id,chain_sequence,hash,content_fingerprint from audit.source_binding where source_key=?")) {
                q.setString(1,source.key());try(var r=q.executeQuery()){if(r.next()) {
                    var receipt=new AuditReceipt(source,Ulid.parse(r.getString(1)),r.getString(2),r.getLong(3),r.getString(4),r.getString(5));
                    receipt.requireMatches(source,draft);return receipt;
                }}
            }
            var event=new AuditTrail(db.dataSource).append(draft);
            var receipt=new AuditReceipt(source,event.id(),AuditDraftFingerprint.chainOf(event),event.sequence(),event.hash(),AuditDraftFingerprint.of(event));
            receipt.requireMatches(source,draft);
            try(var q=c.prepareStatement("""
                insert into audit.source_binding(source_key,schema_version,scope_fingerprint,key_fingerprint,transition,
                  source_sequence,content_fingerprint,event_id,chain_id,chain_sequence,hash) values(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
                q.setString(1,source.key());q.setInt(2,source.schemaVersion());q.setString(3,source.scopeFingerprint());q.setString(4,source.keyFingerprint());
                q.setString(5,source.transition().name());q.setLong(6,source.sequence());q.setString(7,source.contentFingerprint());q.setString(8,event.id().value());
                q.setString(9,receipt.chainId());q.setLong(10,event.sequence());q.setString(11,event.hash());q.executeUpdate();
            }
            return receipt;
        }catch(SQLException failure){throw new StoreUnavailableException("central DATA fixture failed",failure);}
    }
    Target target(Fixture f){return new Target(f.command().target());}
    @Test void intentAndDispatchReceiptsPrecedeSendAndOutcomeReceiptPrecedesPrivateResult() {
        var f=fixture();var t=target(f);var g=gate(this::fixtureReceipt);var phases=new ArrayList<EffectExecutionFence.Boundary>();
        t.send=()->{assertEquals(0,pending(f.command().key()));assertEquals(List.of(AuditSource.Transition.INTENT,AuditSource.Transition.DISPATCH),history(f.command().key()).stream().map(x->x.source().transition()).toList());return new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,"safe");};
        Effect result=g.invoke(f.invocation(),t,(i,b)->{assertEquals(f.invocation(),i);phases.add(b);if(b!=EffectExecutionFence.Boundary.PREPARE)assertEquals(0,pending(i.command().key()));});
        assertEquals(EffectState.CONFIRMED,result.state());assertEquals(1,t.sends.get());assertEquals(0,t.reads.get());assertEquals(3,history(f.command().key()).size());
        assertEquals(List.of(EffectExecutionFence.Boundary.PREPARE,EffectExecutionFence.Boundary.DISPATCH,EffectExecutionFence.Boundary.RESULT),phases);
    }
    @Test void auditOutageBeforeDispatchLeavesOriginalAttemptUncertainAndMakesNoTargetCall() {
        var f=fixture();var t=target(f);
        var broken=gate((s,d)->{if(s.transition()==AuditSource.Transition.DISPATCH)throw new IllegalStateException("audit unavailable");return fixtureReceipt(s,d);});
        assertThrows(IllegalStateException.class,()->broken.invoke(f.invocation(),t,current()));
        assertEquals(0,t.sends.get());assertEquals(0,t.reads.get());assertEquals(EffectState.INTENDED,effect(f).state());assertEquals(1,pending(f.command().key()));
        var original=history(f.command().key()).getFirst();
        assertEquals(EffectState.INTENDED,gate(this::fixtureReceipt).invoke(f.invocation(),t,current()).state());
        assertEquals(0,t.sends.get());assertEquals(1,t.reads.get());assertEquals(original,history(f.command().key()).getFirst());assertEquals(0,pending(f.command().key()));
    }
    @Test void lostCentralDataFixtureReplyDoesNotDispatchAndFreshComponentReusesOriginalFacts() {
        var f=fixture();var t=target(f);var lost=new AtomicBoolean(true);
        assertThrows(IllegalStateException.class,()->gate((s,d)->{var receipt=fixtureReceipt(s,d);if(lost.compareAndSet(true,false))throw new IllegalStateException("reply lost after fixture binding");return receipt;}).invoke(f.invocation(),t,current()));
        assertFalse(lost.get());assertEquals(0,t.sends.get());var saved=history(f.command().key());assertEquals(2,saved.size());
        gate(this::fixtureReceipt).invoke(f.invocation(),t,current());assertEquals(saved,history(f.command().key()).subList(0,2));assertEquals(0,t.sends.get());assertEquals(1,t.reads.get());
    }
    @Test void failedOutcomeAuditRetainsConfirmedFactAndRetryReturnsOnlyAfterItsReceipt() {
        var f=fixture();var t=target(f);
        assertThrows(IllegalStateException.class,()->gate((s,d)->{if(s.transition()==AuditSource.Transition.OUTCOME)throw new IllegalStateException("outcome audit unavailable");return fixtureReceipt(s,d);}).invoke(f.invocation(),t,current()));
        assertEquals(EffectState.CONFIRMED,effect(f).state());assertEquals(1,pending(f.command().key()));assertEquals(1,t.sends.get());
        var saved=history(f.command().key());assertEquals(EffectState.CONFIRMED,gate(this::fixtureReceipt).invoke(f.invocation(),t,current()).state());
        assertEquals(saved,history(f.command().key()));assertEquals(1,t.sends.get());assertEquals(0,t.reads.get());assertEquals(0,pending(f.command().key()));
    }
    @Test void registryClosureOrDeadlineDuringBlockingAuditMakesNoTargetCall() {
        for(boolean deadline:List.of(false,true)) {
            var f=fixture();var t=target(f);var once=new AtomicBoolean(true);
            assertThrows(IllegalStateException.class,()->gate((s,d)->{var receipt=fixtureReceipt(s,d);if(s.transition()==AuditSource.Transition.DISPATCH && once.compareAndSet(true,false)){if(deadline)expire(f);else end(f);}return receipt;}).invoke(f.invocation(),t,current()));
            assertFalse(once.get());assertEquals(0,t.sends.get());assertEquals(0,t.reads.get());assertEquals(EffectState.INTENDED,effect(f).state());assertEquals(0,pending(f.command().key()));
        }
    }
    @Test void privateFenceDenialAfterAuditIsNotSwallowedAsATargetUnknown() {
        var f=fixture();var t=target(f);var live=new AtomicBoolean(true);
        EffectExecutionFence guard=(i,b)->{if(!live.get())throw new SecurityException("private protocol fence closed");};
        assertThrows(SecurityException.class,()->gate((s,d)->{var receipt=fixtureReceipt(s,d);if(s.transition()==AuditSource.Transition.DISPATCH)live.set(false);return receipt;}).invoke(f.invocation(),t,guard));
        assertEquals(0,t.sends.get());assertEquals(0,t.reads.get());assertEquals(2,history(f.command().key()).size());
    }
    @Test void currentRefusalAfterTargetRetainsAndAuditsOutcomeButReleasesNoPrivateResult() {
        var f=fixture();var t=target(f);t.send=()->{end(f);return new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,null);};
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,current()));
        assertEquals(EffectState.CONFIRMED,effect(f).state());assertEquals(3,history(f.command().key()).size());assertEquals(0,pending(f.command().key()));assertEquals(1,t.sends.get());
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,current()));assertEquals(1,t.sends.get());
    }
    @Test void evenCachedResultsRefreshConsumerReadbackAndCurrentAuthority() {
        var f=fixture();var t=target(f);gate(this::fixtureReceipt).invoke(f.invocation(),t,current());var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->gate((s,d)->{calls.incrementAndGet();throw new IllegalStateException("cached receipt cannot replace readback");}).invoke(f.invocation(),t,current()));
        assertEquals(1,calls.get());assertEquals(1,t.sends.get());assertEquals(0,t.reads.get());
        assertThrows(SecurityException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,(i,b)->{if(b==EffectExecutionFence.Boundary.RESULT)throw new SecurityException("current result fence denied");}));assertEquals(1,t.sends.get());
    }
    @Test void wrongFullOwnerLegacyIntentOrChangedRequestCannotReachTheTarget() {
        var f=fixture();var t=target(f);var wrong=new EffectScope(f.scope().tenant(),f.scope().instanceId(),new Subject(SubjectKind.AGENT,OWNER.id()));
        assertEquals(f.scope().threadId(),wrong.threadId());
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(new EffectInvocation(wrong,f.command(),null),t,current()));assertTrue(ledger.find(f.command().key()).isEmpty());
        new JdbcEffectLedger(db.dataSource).prepare(f.command(),Duration.ZERO,Instant.now());
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,current()));assertEquals(0,t.sends.get());
        var other=fixture();var actual=target(other);gate(this::fixtureReceipt).invoke(other.invocation(),actual,current());
        var changed=new EffectCommand(other.command().key(),other.command().target(),"f".repeat(64));
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(new EffectInvocation(other.scope(),changed,null),actual,current()));assertEquals(1,actual.sends.get());
        assertThrows(IllegalArgumentException.class,()->new EffectInvocation(other.scope(),f.command(),null));
        assertThrows(IllegalArgumentException.class,()->new EffectInvocation(other.scope(),other.command(),other.command().key()));
    }
    @Test void lookupAndReplayBothRequireReceiptsAndEachReplayRetainsAnotherDispatchFact() {
        var f=fixture();ledger.prepareScoped(f.scope(),f.command(),Duration.ofMinutes(5),Instant.now());ledger.markAttempted(f.command().key());
        var t=target(f);t.window=Duration.ofMinutes(5);var boundaries=new ArrayList<EffectExecutionFence.Boundary>();
        t.read=()->{assertEquals(0,pending(f.command().key()));return new EffectTarget.Reply(EffectTarget.Outcome.UNKNOWN,null);};
        t.send=()->{assertEquals(0,pending(f.command().key()));assertEquals(2,history(f.command().key()).stream().filter(v->v.source().transition()==AuditSource.Transition.DISPATCH).count());return new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,null);};
        assertEquals(EffectState.CONFIRMED,gate(this::fixtureReceipt).invoke(f.invocation(),t,(i,b)->boundaries.add(b)).state());
        assertEquals(1,t.reads.get());assertEquals(1,t.sends.get());assertTrue(boundaries.containsAll(List.of(EffectExecutionFence.Boundary.LOOKUP,EffectExecutionFence.Boundary.DISPATCH)));
    }
    @Test void replayRetentionExpiringDuringMandatoryAuditPreventsTheResend() {
        var f=fixture();ledger.prepareScoped(f.scope(),f.command(),Duration.ofSeconds(4),Instant.now());ledger.markAttempted(f.command().key());
        long initialDispatch=history(f.command().key()).getLast().source().sequence();
        var t=target(f);t.window=Duration.ofSeconds(4);var delay=new AtomicBoolean(true);
        assertThrows(IllegalStateException.class,()->gate((s,d)->{var receipt=fixtureReceipt(s,d);if(s.transition()==AuditSource.Transition.DISPATCH && s.sequence()!=initialDispatch && delay.compareAndSet(true,false))sleep(4300);return receipt;}).invoke(f.invocation(),t,current()));
        assertFalse(delay.get());assertEquals(0,t.sends.get());assertEquals(1,t.reads.get());
    }
    static void sleep(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void replayRetentionIsSampledAfterTheBlockingDispatchFence() {
        var f=fixture();ledger.prepareScoped(f.scope(),f.command(),Duration.ofSeconds(4),Instant.now());ledger.markAttempted(f.command().key());
        var t=target(f);t.window=Duration.ofSeconds(4);
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,(i,b)->{if(b==EffectExecutionFence.Boundary.DISPATCH)sleep(4300);}));
        assertEquals(1,t.reads.get());assertEquals(0,t.sends.get());assertEquals(EffectState.INTENDED,effect(f).state());
    }
    @Test void compensationRequiresItsReceiptsBeforeUndoAndOriginalFactBeforeResult() {
        var f=fixture();var first=target(f);var g=gate(this::fixtureReceipt);g.invoke(f.invocation(),first,current());
        var undo=f.scope().command("refund","undo/1","approved-refund","amount=10");var t=new Target(undo.target());
        t.send=()->{assertEquals(0,pending(undo.key()));assertEquals(0,pending(f.command().key()));return new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,null);};
        var invocation=new EffectInvocation(f.scope(),undo,f.command().key());
        assertEquals(EffectState.COMPENSATED,g.invoke(invocation,t,(i,b)->{assertEquals(invocation,i);}).state());
        assertEquals(EffectState.CONFIRMED,ledger.find(undo.key()).orElseThrow().state());assertEquals(0,pending(undo.key()));assertEquals(0,pending(f.command().key()));assertEquals(1,t.sends.get());
        assertEquals(EffectState.COMPENSATED,gate(this::fixtureReceipt).invoke(invocation,t,current()).state());assertEquals(1,t.sends.get());
        // Ordinary cached reads of the original also require the actual confirmed undo's receipt readback.
        assertThrows(IllegalStateException.class,()->gate((s,d)->{if(d.attributes().get("effect.purpose").equals("COMPENSATION"))throw new IllegalStateException("undo readback unavailable");return fixtureReceipt(s,d);}).invoke(f.invocation(),first,current()));assertEquals(1,first.sends.get());
    }
    @Test void failedOrUnknownCompensationNeverMarksTheOriginalCompensated() {
        for(var outcome:List.of(EffectTarget.Outcome.FAILED,EffectTarget.Outcome.UNKNOWN)) {
            var f=fixture();gate(this::fixtureReceipt).invoke(f.invocation(),target(f),current());var undo=f.scope().command("refund","undo","approved-refund","amount=10");
            var t=new Target(undo.target());t.send=()->new EffectTarget.Reply(outcome,null);
            assertEquals(EffectState.CONFIRMED,gate(this::fixtureReceipt).invoke(new EffectInvocation(f.scope(),undo,f.command().key()),t,current()).state());
            assertEquals(EffectState.CONFIRMED,effect(f).state());assertEquals(1,ledger.manualQueue(f.scope().threadId(),20).stream().filter(x->x.key().equals(f.command().key())).count());
            assertEquals(0,pending(f.command().key()));assertEquals(0,pending(undo.key()));
        }
    }
    @Test void missingQueueAndOverBoundHistoryRefuseInsteadOfAcceptingAPartialPage() throws Exception {
        var f=fixture();ledger.prepareScoped(f.scope(),f.command(),Duration.ZERO,Instant.now());
        try(var c=db.dataSource.getConnection()) {
            EffectAuditTestDatabase.sql(c,"alter table effect.audit_delivery disable trigger user");
            try(var q=c.prepareStatement("delete from effect.audit_delivery where source_event_id=?")){q.setLong(1,history(f.command().key()).getFirst().source().sequence());q.executeUpdate();}
            finally{EffectAuditTestDatabase.sql(c,"alter table effect.audit_delivery enable trigger user");}
        }
        var t=target(f);var sinkCalls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->gate((s,d)->{sinkCalls.incrementAndGet();return fixtureReceipt(s,d);}).invoke(f.invocation(),t,current()));assertEquals(0,sinkCalls.get());assertEquals(0,t.sends.get());
        var many=fixture();ledger.prepareScoped(many.scope(),many.command(),Duration.ZERO,Instant.now());
        for(int i=0;i<128;i++)ledger.requireManualResolution(many.command().key(),"effect.outcome.unknown");
        var other=target(many);assertThrows(IllegalStateException.class,()->gate((s,d)->{sinkCalls.incrementAndGet();return fixtureReceipt(s,d);}).invoke(many.invocation(),other,current()));assertEquals(0,sinkCalls.get());assertEquals(0,other.sends.get());
    }
    @Test void concurrentComponentsKeepTheExistingCrossConnectionEffectExclusion() throws Exception {
        var f=fixture();var t=target(f);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        t.send=()->{entered.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,null);};
        var future=executor.submit(()->gate(this::fixtureReceipt).invoke(f.invocation(),t,current()));
        try{assertTrue(entered.await(8,TimeUnit.SECONDS));assertThrows(JdbcEffectLedger.EffectBusyException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,current()));release.countDown();assertEquals(EffectState.CONFIRMED,future.get(8,TimeUnit.SECONDS).state());assertEquals(1,t.sends.get());}
        finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(8,TimeUnit.SECONDS));}
    }
    @Test void callerJtaIsSuspendedAcrossFenceAuditTargetAndTelemetryCleanup() throws Exception {
        var f=fixture();var t=target(f);var hooks=new AtomicInteger();Runnable probe=()->{try{assertNull(manager.getTransaction());hooks.incrementAndGet();}catch(SystemException e){throw new AssertionError(e);}};
        t.send=()->{probe.run();return new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,null);};
        var tracing=new EffectTelemetry(OpenTelemetry.noop()){@Override public Effect effect(Operation op,Supplier<Effect> work){var answer=super.effect(op,work);probe.run();return answer;}};
        var g=new AuditedEffects(db.dataSource,manager,(s,d)->{probe.run();return fixtureReceipt(s,d);},tracing);
        caller.begin();try{assertEquals(EffectState.CONFIRMED,g.invoke(f.invocation(),t,(i,b)->probe.run()).state());assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertTrue(hooks.get()>=6);assertEquals(EffectState.CONFIRMED,effect(f).state());assertEquals(0,pending(f.command().key()));
    }
    @Test void registryClosureDuringTelemetryCleanupStillRefusesPrivateOutput() {
        var f=fixture();var t=target(f);
        var tracing=new EffectTelemetry(OpenTelemetry.noop()){@Override public Effect effect(Operation op,Supplier<Effect> work){var answer=super.effect(op,work);end(f);return answer;}};
        assertThrows(IllegalStateException.class,()->new AuditedEffects(db.dataSource,manager,this::fixtureReceipt,tracing).invoke(f.invocation(),t,current()));
        assertEquals(EffectState.CONFIRMED,effect(f).state());assertEquals(1,t.sends.get());assertEquals(0,pending(f.command().key()));
    }
    @Test void changedHistoryDuringAuditCannotHideAnUnacknowledgedTail() {
        var f=fixture();var t=target(f);var once=new AtomicBoolean(true);
        assertThrows(IllegalStateException.class,()->gate((s,d)->{
            var receipt=fixtureReceipt(s,d);
            if(once.compareAndSet(true,false))ledger.requireManualResolution(f.command().key(),"effect.outcome.unknown");
            return receipt;
        }).invoke(f.invocation(),t,current()));
        assertFalse(once.get());assertEquals(3,history(f.command().key()).size());assertEquals(1,pending(f.command().key()));assertEquals(0,t.sends.get());assertEquals(0,t.reads.get());
    }
    @Test void inventedReceiptAndUnavailableCentralMetadataNeverReachTheTarget() throws Exception {
        var f=fixture();var t=target(f);
        assertThrows(IllegalStateException.class,()->gate((s,d)->new AuditReceipt(s,d.id(),AuditDraftFingerprint.chainOf(d),1,"f".repeat(64),s.contentFingerprint())).invoke(f.invocation(),t,current()));
        assertEquals(0,t.sends.get());assertEquals(2,pending(f.command().key()));
        try(var c=db.dataSource.getConnection()){EffectAuditTestDatabase.sql(c,"alter table audit.source_binding rename to temporarily_missing_source_binding");}
        try{assertThrows(StoreUnavailableException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,current()));}
        finally{try(var c=db.dataSource.getConnection()){EffectAuditTestDatabase.sql(c,"alter table audit.temporarily_missing_source_binding rename to source_binding");}}
        assertEquals(0,t.sends.get());assertEquals(0,t.reads.get());
    }
    @Test void unprovenCompensatedHistoryCannotReleaseACachedResultOrCreateAnotherUndo() {
        var f=fixture();var t=target(f);gate(this::fixtureReceipt).invoke(f.invocation(),t,current());
        ledger.recordOutcome(f.command().key(),EffectState.COMPENSATED,null); // Trusted raw persistence can assert history; NOT operator authority.
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(f.invocation(),t,current()));assertEquals(1,t.sends.get());
        var undo=f.scope().command("refund","undo","approved-refund","amount=10");var other=new Target(undo.target());
        assertThrows(IllegalStateException.class,()->gate(this::fixtureReceipt).invoke(new EffectInvocation(f.scope(),undo,f.command().key()),other,current()));
        assertTrue(ledger.find(undo.key()).isEmpty());assertEquals(0,other.sends.get());
    }
    @Test void interruptionDuringAuditRetainsAttemptAndFlagWithoutTargetIo() {
        var f=fixture();var t=target(f);var once=new AtomicBoolean(true);
        try{assertThrows(CancellationException.class,()->gate((s,d)->{var receipt=fixtureReceipt(s,d);if(s.transition()==AuditSource.Transition.DISPATCH && once.compareAndSet(true,false))Thread.currentThread().interrupt();return receipt;}).invoke(f.invocation(),t,current()));assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();} // Clear ONLY the test's own thread after proving flag preservation.
        assertFalse(once.get());assertEquals(0,t.sends.get());assertEquals(0,t.reads.get());assertEquals(EffectState.INTENDED,effect(f).state());assertEquals(2,history(f.command().key()).size());
    }
}
