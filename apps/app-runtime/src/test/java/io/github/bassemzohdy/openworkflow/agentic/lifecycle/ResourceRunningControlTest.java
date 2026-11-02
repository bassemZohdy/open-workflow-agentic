package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceInvocations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.execution.GovernedExecutionRoot;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.CompositeExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Real PG/control/identity mechanics. Fixture verifier and identity-only fence are NOT production composition. */
class ResourceRunningControlTest {
    @Test void managedAsyncReturnsOnlyAfterSynchronousWorkerEnds() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var caller=Thread.currentThread();
            var local=new InheritableThreadLocal<String>();local.set("must-not-propagate");
            var ordinary=new ThreadLocal<String>();var firstWorker=new AtomicReference<Thread>();
            try {
                var paired=CredentialFence.pair(f.binding(),entry->{});
                var result=gate.executeGovernedAsync(f.binding(),f.entry(1),UUID.randomUUID(),paired,r->{
                    assertNotSame(caller,Thread.currentThread());
                    assertNull(local.get(),"worker must not inherit ambient thread context");
                    firstWorker.set(Thread.currentThread());ordinary.set("private-worker-value");
                    r.requireCurrent(f.entry(1));return "fixture";
                });
                assertThrows(JsonProcessingException.class,()->new ObjectMapper().writeValueAsString(result));
                var completed=result.get(10,TimeUnit.SECONDS);
                assertEquals(Kind.CALLED,completed.kind());assertEquals("fixture",completed.value());
                assertEquals(0,gate.activeCount());
                assertFalse(result.cancel(true),"completed work cannot be cancelled");
                awaitWorkerCount(gate,0);
                var next=fixture(db,"a".repeat(64));var nextPair=CredentialFence.pair(next.binding(),entry->{});
                var second=gate.executeGovernedAsync(next.binding(),next.entry(1),UUID.randomUUID(),nextPair,r->{
                    assertNotSame(firstWorker.get(),Thread.currentThread());
                    assertNull(ordinary.get(),"ordinary thread locals must not leak across attempts");
                    return "second";
                });
                assertEquals("second",second.get(10,TimeUnit.SECONDS).value());
            } finally {local.remove();}
        }
    }

    @Test void cancellingAsyncWaiterKeepsCapacityUntilWorkerActuallyExits() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var entered=new CountDownLatch(1);
            var release=new CountDownLatch(1);var control=new AtomicReference<Running>();
            try {
                var paired=CredentialFence.pair(f.binding(),entry->{});
                var result=gate.executeGovernedAsync(f.binding(),f.entry(1),UUID.randomUUID(),paired,r->{
                    control.set(r);entered.countDown();
                    try {
                        if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");
                        assertThrows(RefusedException.class,r::requireCurrent);
                        return "private output";
                    } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                });
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertTrue(result.cancel(true));assertTrue(result.isCancelled());
                assertThrows(CancellationException.class,result::get);
                assertThrows(RefusedException.class,control.get()::requireCurrent);
                assertEquals(1,gate.activeCount(),"cancel is not physical termination");
                assertEquals(1,gate.reservedWorkerCount());
                assertThrows(RefusedException.class,()->gate.executeGovernedAsync(
                        f.binding(),f.entry(1),UUID.randomUUID(),paired,r->"must not run"));
                release.countDown();awaitActiveCount(gate,0);awaitWorkerCount(gate,0);
                var retained=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),paired)
                        .executeGoverned(r->{fail("the committed entry must not run twice");return "duplicate";});
                assertEquals(Kind.RETAINED,retained.kind());assertNull(retained.value());
            } finally {release.countDown();}
        }
    }

    @Test void closingAsyncGateDoesNotReleaseRunningWorkerEarly() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var entered=new CountDownLatch(1);
            var release=new CountDownLatch(1);
            try {
                var paired=CredentialFence.pair(f.binding(),entry->{});
                var result=gate.executeGovernedAsync(f.binding(),f.entry(1),UUID.randomUUID(),paired,r->{
                    entered.countDown();
                    try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    return "private output";
                });
                assertTrue(entered.await(10,TimeUnit.SECONDS));gate.close();
                assertEquals(1,gate.activeCount());assertEquals(1,gate.reservedWorkerCount());assertFalse(result.isDone());
                release.countDown();
                var refused=assertThrows(ExecutionException.class,()->result.get(10,TimeUnit.SECONDS));
                assertInstanceOf(RefusedException.class,refused.getCause());
                assertEquals(0,gate.activeCount());assertTrue(gate.find(f.binding().scope()).isPresent());
                assertThrows(RefusedException.class,()->gate.executeGovernedAsync(
                        f.binding(),f.entry(1),UUID.randomUUID(),paired,r->"must not run"));
            } finally {release.countDown();}
        }
    }

    @Test void cancellationDuringAdmissionKeepsCapacityAndDoesNotCreateEntry() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var entered=new CountDownLatch(1);
            var release=new CountDownLatch(1);var callbacks=new AtomicInteger();
            try {
                var paired=CredentialFence.pair(f.binding(),entry->{
                    entered.countDown();
                    try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                });
                var result=gate.executeGovernedAsync(f.binding(),f.entry(1),UUID.randomUUID(),paired,
                        r->{callbacks.incrementAndGet();return "private";});
                assertTrue(entered.await(10,TimeUnit.SECONDS));assertTrue(result.cancel(false));
                assertEquals(1,gate.activeCount(),"blocked admission still occupies its worker");
                assertEquals(1,gate.reservedWorkerCount());
                release.countDown();awaitActiveCount(gate,0);awaitWorkerCount(gate,0);
                assertEquals(0,callbacks.get());assertTrue(gate.find(f.binding().scope()).isEmpty());
            } finally {release.countDown();}
        }
    }

    @Test void managedWorkerReturningAnotherStageStillQuarantinesEntry() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var detached=new CompletableFuture<String>();
            var paired=CredentialFence.pair(f.binding(),entry->{});
            var result=gate.executeGovernedAsync(f.binding(),f.entry(1),UUID.randomUUID(),paired,r->detached);
            var refusal=assertThrows(ExecutionException.class,()->result.get(10,TimeUnit.SECONDS));
            assertInstanceOf(RefusedException.class,refusal.getCause());
            awaitWorkerCount(gate,0);
            assertEquals(1,gate.activeCount(),"detached work must retain the entry slot");
            detached.complete("finished");
            assertEquals(1,gate.activeCount(),"stage completion does not prove physical termination");
            assertTrue(gate.find(f.binding().scope()).isPresent());
        }
    }

    private static void awaitActiveCount(JdbcResourceExecutionGate gate,int expected) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(gate.activeCount()!=expected && System.nanoTime()<until)Thread.sleep(20);
        assertEquals(expected,gate.activeCount(),"worker did not actually terminate");
    }

    private static void awaitWorkerCount(JdbcResourceExecutionGate gate,int expected) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(gate.reservedWorkerCount()!=expected && System.nanoTime()<until)Thread.sleep(20);
        assertEquals(expected,gate.reservedWorkerCount(),"worker slot did not retire");
    }

    @Test void observableAsynchronousResultsRefuseAndQuarantineCapacity() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);
            for(int kind=0;kind<4;kind++) {
                var fixture=fixture(db,"a".repeat(64));
                var control=new AtomicReference<Running>();
                var stage=new CompletableFuture<String>();
                var task=new FutureTask<>(()->"unstarted");
                Flow.Publisher<String> publisher=subscriber->{throw new AssertionError("must not subscribe");};
                Object result=switch(kind) {
                    case 0 -> stage;
                    case 1 -> stage.minimalCompletionStage();
                    case 2 -> task;
                    default -> publisher;
                };
                try(var gate=new JdbcResourceExecutionGate(db.owner,1)) {
                    var paired=CredentialFence.pair(fixture.binding(),entry->{});
                    var attempt=gate.openGoverned(fixture.binding(),fixture.entry(1),UUID.randomUUID(),paired);
                    assertThrows(RefusedException.class,()->attempt.executeGoverned(r->{control.set(r);return result;}));
                    assertNotNull(control.get());
                    assertThrows(RefusedException.class,control.get()::requireCurrent);
                    assertTrue(gate.find(fixture.binding().scope()).isPresent(),"the committed entry remains");
                    assertEquals(1,gate.activeCount(),"uncertain detached work retains the slot");
                    stage.cancel(true);task.cancel(true);
                    assertEquals(1,gate.activeCount(),"future completion or cancellation does not prove physical termination");
                    assertThrows(RefusedException.class,()->gate.openGoverned(
                            fixture.binding(),fixture.entry(1),UUID.randomUUID(),paired));
                    gate.close();
                    assertEquals(1,gate.activeCount(),"close must not claim detached work terminated");
                }
                try(var reopened=new JdbcResourceExecutionGate(db.owner,1)) {
                    var paired=CredentialFence.pair(fixture.binding(),entry->{});
                    var retained=reopened.openGoverned(fixture.binding(),fixture.entry(1),UUID.randomUUID(),paired)
                            .executeGoverned(r->{fail("retained entry must not dispatch again");return "duplicate";});
                    assertEquals(Kind.RETAINED,retained.kind());
                }
            }
        }
    }

    @Test void detachedWorkerCannotReuseControlAfterReturningAStage() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var entered=new CountDownLatch(1);
            var release=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
            var stage=new CompletableFuture<String>();var work=new AtomicReference<Future<?>>();
            try {
                var paired=CredentialFence.pair(f.binding(),entry->{});
                var attempt=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),paired);
                assertThrows(RefusedException.class,()->attempt.executeGoverned(r->{
                    work.set(worker.submit(()->{
                        entered.countDown();
                        try {
                            if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");
                            assertThrows(RefusedException.class,r::requireCurrent);
                            stage.complete("finished");
                        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    }));
                    try {if(!entered.await(8,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    return stage;
                }));
                assertEquals(1,gate.activeCount());
                release.countDown();work.get().get(10,TimeUnit.SECONDS);
                assertEquals("finished",stage.get(5,TimeUnit.SECONDS));
                assertEquals(1,gate.activeCount(),"the gate cannot infer actual termination from a returned stage");
            } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }

    @Test void legacyCallbackCannotReturnAStageEither() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            var attempt=gate.open(f.binding(),f.entry(1),UUID.randomUUID(),entry->{});
            assertThrows(RefusedException.class,()->attempt.execute(entry->CompletableFuture.completedFuture("value")));
            assertEquals(1,gate.activeCount());
            assertTrue(gate.find(f.binding().scope()).isPresent());
        }
    }

    @Test void onlyAFreshGovernedCallbackReceivesControlAndItCannotOutliveItsAttempt() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var checks=new AtomicInteger();
            var fence=CredentialFence.pair(f.binding(),entry->{assertEquals(f.entry(1),entry);checks.incrementAndGet();});
            var attempt=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),fence);
            var result=attempt.executeGoverned(running->{
                running.requireCurrent(f.entry(1));
                assertThrows(RefusedException.class,()->running.requireCurrent(f.entry(2)));
                assertThrows(NotSerializableException.class,()->new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(running));
                assertThrows(JsonProcessingException.class,()->new ObjectMapper().writeValueAsString(running));
                assertThrows(NotSerializableException.class,()->new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(fence));
                assertThrows(JsonProcessingException.class,()->new ObjectMapper().writeValueAsString(fence));
                return running;
            });
            assertEquals(Kind.CALLED,result.kind());assertTrue(checks.get()>0);
            var captured=result.value();assertEquals(f.entry(1),captured.entry());
            assertThrows(RefusedException.class,captured::requireCurrent);captured.close();captured.close();
            assertEquals(0,gate.activeCount());
            var again=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),fence)
                    .executeGoverned(running->{fail("retained data must not mint running control");return running;});
            assertEquals(Kind.RETAINED,again.kind());assertNull(again.value());
        }
    }
    @Test void wrongCredentialAssociationAndWrongCallbackModeRefuseWithoutEntry() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var now=databaseNow(db.owner);
            var other=credential(db,f,now.minusSeconds(1),now.plusSeconds(120));
            assertEquals(other.scope(),f.binding().scope());
            assertThrows(RefusedException.class,()->gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(other,e->{ })));
            assertEquals(0,gate.activeCount());
            try(var plain=gate.open(f.binding(),f.entry(1),UUID.randomUUID(),e->{ })) {
                assertThrows(RefusedException.class,()->plain.executeGoverned(r->{fail("legacy data callback cannot expose control");return null;}));
            }
            try(var governed=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(f.binding(),e->{ }))) {
                assertThrows(RefusedException.class,()->governed.execute(e->{fail("governed path requires private control");return null;}));
            }
            assertTrue(gate.find(f.binding().scope()).isEmpty());
        }
    }
    @Test void aNodeOnAnotherThreadObservesCloseAndCapacityWaitsForItsActualTermination() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            var caller=Executors.newSingleThreadExecutor();var node=Executors.newSingleThreadExecutor();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var captured=new AtomicReference<Running>();
            var parentThread=new AtomicReference<Thread>();
            var attempt=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(f.binding(),e->{ }));
            try {
                var work=caller.submit(()->attempt.executeGoverned(running->{
                    parentThread.set(Thread.currentThread());captured.set(running);
                    try {
                        var action=node.submit(()->{
                            assertNotEquals(parentThread.get(),Thread.currentThread());running.requireCurrent(f.entry(1));
                            entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));
                            assertThrows(RefusedException.class,running::requireCurrent);return "private-node-value";
                        });
                        return assertDoesNotThrow(()->action.get(10,TimeUnit.SECONDS));
                    } finally {
                        node.shutdown();assertTrue(assertDoesNotThrow(()->node.awaitTermination(10,TimeUnit.SECONDS)));
                    }
                }));
                assertTrue(entered.await(10,TimeUnit.SECONDS));captured.get().close();
                assertThrows(RefusedException.class,captured.get()::requireCurrent);
                assertEquals(1,gate.activeCount());assertFalse(work.isDone());
                assertThrows(RefusedException.class,()->gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(f.binding(),e->{ })));
                release.countDown();var failure=assertThrows(ExecutionException.class,()->work.get(10,TimeUnit.SECONDS));
                assertInstanceOf(RefusedException.class,failure.getCause());assertEquals(0,gate.activeCount());
                assertTrue(node.isTerminated());assertThrows(RefusedException.class,captured.get()::requireCurrent);
            } finally {
                release.countDown();node.shutdownNow();caller.shutdownNow();
                assertTrue(node.awaitTermination(10,TimeUnit.SECONDS));assertTrue(caller.awaitTermination(10,TimeUnit.SECONDS));
            }
        }
    }
    @Test void realOpaquePairingAndCanonicalLookupFeedTheGateWithoutCredentialSwapping() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,2)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64),Pep.AGENT_RESEARCH_RUN);var b=f.binding();
            var now=databaseNow(db.owner);var b2=credential(db,f,now.minusSeconds(1),now.plusSeconds(120));
            Subject expectedWorkload=new Subject(SubjectKind.WORKFLOW,"execution-schema-fixture"); // independent server fixture pin
            Clock clock=Clock.systemUTC();var ids=UlidGenerator.shared();
            var grants=new DelegationGrants(new DelegationGrantStore(db.owner),new IdentityAudit(event->{ },clock,ids),clock,ids,Duration.ofHours(2));
            var issued=new JdbcIssuedTokenBindings(db.owner);var calls=new JdbcResourceCallBindings(db.owner);
            var tokens=new HashMap<String,VerifiedToken>();
            for(var binding:List.of(b,b2))tokens.put("fixture-only-bearer-"+binding.tokenId(),new VerifiedToken(
                    binding.issuer(),binding.scope().owner(),Set.of(binding.scope().audience()),Set.of(AgentResourceAdmission.SCOPE),
                    binding.scope().tenant(),binding.issuedAt(),binding.expiresAt(),binding.tokenId(),List.of(expectedWorkload.id())));
            var guard=new ResourceTokenGuard((bearer,audience)->{
                var token=tokens.get(bearer);if(token==null)throw new TokenRejectedException(TokenRejectedException.Reason.SIGNATURE);return token;
            },b.scope().audience(),new JdbcReplayCache(db.owner),grants,issued,clock,1);
            var admission=new AgentResourceAdmission(guard,calls,issued,b.scope().audience(),clock);
            try {
                var first=admission.bind(admission.admitCall("fixture-only-bearer-"+b.tokenId()),f.entry(1));
                var second=admission.bind(admission.admitCall("fixture-only-bearer-"+b2.tokenId()),f.entry(1));
                var one=calls.findBinding(first.bindingKey()).orElseThrow();var two=calls.findBinding(second.bindingKey()).orElseThrow();
                assertEquals(b,one);assertEquals(b2,two);assertNotEquals(first.bindingKey(),second.bindingKey());
                var live=new LiveIdentityCheck(admission);var callbacks=new AtomicInteger();
                assertThrows(IllegalArgumentException.class,
                        ()->new LiveIdentityResourceFence(admission,first,f.entry(1),two,expectedWorkload,calls));
                var identity=new LiveIdentityResourceFence(admission,first,f.entry(1),one,expectedWorkload,calls);
                assertDoesNotThrow(()->identity.requireCurrent(f.entry(1)));
                assertThrows(JsonProcessingException.class,()->new ObjectMapper().writeValueAsString(identity));
                var wrongComposite=CompositeExecutionFence.builder();
                for(var check:CompositeExecutionFence.Check.values())wrongComposite.with(check,ignored->{});
                var pep=new Pep(request->new Decision("fixture",DecisionOutcome.ALLOW,"fixture",
                        PolicyRef.FAIL_CLOSED,clock.instant()),event->{},Duration.ofSeconds(3),clock);
                var policy=new LiveResearchPolicyFence(admission,first,f.entry(1),pep);
                assertThrows(IllegalStateException.class,()->GovernedExecutionRoot.pair(one,identity,policy,null,wrongComposite.build()));
                var otherPolicy=new LiveResearchPolicyFence(admission,second,f.entry(1),pep);
                var swappedPolicyComposite=CompositeExecutionFence.builder();
                for(var check:CompositeExecutionFence.Check.values())
                    swappedPolicyComposite.with(check,check==CompositeExecutionFence.Check.IDENTITY?identity:
                            check==CompositeExecutionFence.Check.POLICY?otherPolicy:ignored->{});
                assertThrows(IllegalStateException.class,()->GovernedExecutionRoot.pair(one,identity,otherPolicy,null,swappedPolicyComposite.build()));
                var policyEntered=new CountDownLatch(1);var policyRelease=new CountDownLatch(1);
                var blockingPep=new Pep(request->{
                    policyEntered.countDown();
                    try {if(!policyRelease.await(8,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    return new Decision("fixture",DecisionOutcome.ALLOW,"fixture",PolicyRef.FAIL_CLOSED,clock.instant());
                },event->{},Duration.ofSeconds(10),clock);
                try(var closingPolicy=new LiveResearchPolicyFence(admission,first,f.entry(1),blockingPep)) {
                    var checking=CompletableFuture.runAsync(()->closingPolicy.requireCurrent(f.entry(1)));
                    try {assertTrue(policyEntered.await(8,TimeUnit.SECONDS));closingPolicy.close();}
                    finally {policyRelease.countDown();}
                    assertThrows(CompletionException.class,checking::join);
                }
                var swapped=gate.openGoverned(one,f.entry(1),UUID.randomUUID(),CredentialFence.pair(one,
                        entry->live.requireLive(second,entry,expectedWorkload,one)));
                assertThrows(AgentResourceAdmission.WitnessRefusedException.class,()->swapped.executeGoverned(r->{callbacks.incrementAndGet();return "unsafe";}));
                assertTrue(gate.find(b.scope()).isEmpty());
                var exact=gate.openGoverned(one,f.entry(1),UUID.randomUUID(),CredentialFence.pair(one,
                        identity));
                assertEquals(Kind.CALLED,exact.executeGoverned(r->{r.requireCurrent(f.entry(1));callbacks.incrementAndGet();return "fixture";}).kind());
                var refreshed=gate.openGoverned(two,f.entry(1),UUID.randomUUID(),CredentialFence.pair(two,
                        entry->live.requireLive(second,entry,expectedWorkload,two)));
                assertEquals(Kind.RETAINED,refreshed.executeGoverned(r->{callbacks.incrementAndGet();return "unsafe";}).kind());
                assertEquals(1,callbacks.get());assertEquals(one.key(),gate.find(b.scope()).orElseThrow().firstBindingKey());
                var changed=new ResourceCallBinding(two.issuer(),two.tokenId(),two.issuingGrantId(),two.scope(),
                        two.issuedAt().minusSeconds(1),two.expiresAt());
                try(var mismatch=new LiveIdentityResourceFence(admission,second,f.entry(1),changed,expectedWorkload,calls)) {
                    assertEquals(two.key(),changed.key());
                    assertThrows(IllegalStateException.class,()->mismatch.requireCurrent(f.entry(1)));
                }
                var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
                ResourceInvocations blocking=new ResourceInvocations() {
                    public ResourceCallBinding bind(ResourceCallBinding value){return calls.bind(value);}
                    public Optional<ResourceCallBinding> find(String issuer,String token){return calls.find(issuer,token);}
                    public Optional<ResourceCallScope> findInvocation(ResourceCallScope scope){return calls.findInvocation(scope);}
                    public Optional<ResourceCallBinding> findBinding(String key){
                        entered.countDown();
                        try {if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                        return calls.findBinding(key);
                    }
                };
                try(var closing=new LiveIdentityResourceFence(admission,first,f.entry(1),one,expectedWorkload,blocking)) {
                    var checking=CompletableFuture.runAsync(()->closing.requireCurrent(f.entry(1)));
                    try {assertTrue(entered.await(8,TimeUnit.SECONDS));closing.close();}
                    finally {release.countDown();}
                    assertThrows(CompletionException.class,checking::join);
                }
                identity.close();
                assertThrows(IllegalStateException.class,()->identity.requireCurrent(f.entry(1)));
            } finally {admission.close();}
        }
    }
}
