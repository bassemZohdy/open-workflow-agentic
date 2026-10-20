package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.TransactionManager;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Actual V79/JDBC callback protocol on owned schemas. Spies are NOT actual model/JWT/PEP/price/transport proof. */
@QuarkusTest
class ModelCallReservationStoreTest extends ResourceAllocationFixture {
    record Active(Arena arena,Fixture fixture,AllocatedResourceTask task,ResourceExecutionEntry entry,UUID executor) implements AutoCloseable {
        ResourceModelCall call(String node) {return call(node,"fictional private prompt",100);}
        ResourceModelCall call(String node,String prompt,int tokens) {
            var a=task.allocation();return ResourceModelCall.of(task,entry,executor,node,
                    new ModelCall(a.contract().id(),a.modelProfile().modelId(),prompt,tokens));
        }
        @Override public void close() throws SQLException {arena.close();}
    }
    static ApprovedModelProfile profile(ApprovedModelProfile p,int calls,long ceiling,Duration time) {
        return new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),p.agent(),p.modelId(),calls,ceiling,time,p.maxOutputTokens());
    }
    Fixture running(Fixture f) {
        if(f.parent()==null)return f;
        var p=f.parent();var next=new AgentTaskRecord(p.taskId(),p.tenant(),p.owner(),p.workflowInstance(),p.threadId(),p.contract(),AgentTaskStatus.RUNNING,p.version()+1);
        tasks.replace(next,p.version());return new Fixture(f.row(),f.binding(),f.root(),f.model(),next,f.allocation());
    }
    Active active(boolean parent) throws Exception {return active(parent,UnaryOperator.identity(),true);}
    Active active(boolean parent,UnaryOperator<ApprovedModelProfile> customize,boolean enter) throws Exception {
        var f=running(fixture(parent,customize));var a=new Arena(f);
        try {
            try(var c=data.getConnection()){a.apply(c,"proposed/V79__model_call_reservations.sql");}
            var task=a.store.allocate(f.allocation());UUID executor=UUID.randomUUID();
            if(enter)executor=enter(a,f);
            return new Active(a,f,task,new ResourceExecutionEntry(f.binding().scope(),1),executor);
        } catch(Exception failed){a.close();throw failed;}
    }
    UUID enter(Arena a,Fixture f) throws SQLException {
        try(var c=data.getConnection()) {
            ResourceExecutionSchemaTest.enter(c,a.schema,f.binding(),1,Map.of());
            try(var q=c.prepareStatement("select entering_executor from "+a.schema+".resource_execution where call_key=?")) {
                q.setString(1,f.binding().scope().key());try(var r=q.executeQuery()){assertTrue(r.next());return r.getObject(1,UUID.class);}
            }
        }
    }
    Fixture sibling(Fixture f,int occurrence) {
        var s=f.binding().scope();var scope=new ResourceCallScope(s.tenant(),s.instanceId(),s.owner(),s.applicationId(),s.engineInstanceId(),s.namespace(),
                s.definition(),s.version(),s.taskPosition(),occurrence,"root/sibling/"+occurrence,s.audience(),s.operation(),s.targetFingerprint(),
                ResourceCallScope.requestFingerprint("fictional approved sibling "+occurrence));
        String jti=UUID.randomUUID().toString();Instant expires=now().plusSeconds(120);
        new JdbcIssuedTokenBindings(data).bind(f.binding().issuer(),jti,new IssuedTokenBindings.Binding(f.root().id(),scope.tenant(),expires));
        var binding=new ResourceCallBinding(f.binding().issuer(),jti,f.root().id(),scope,expires.minusSeconds(120),expires);bindings.bind(binding);
        var old=f.allocation().contract();var contract=new AgentTaskContract(UlidGenerator.shared().next(),"resource-request-sha256:"+scope.requestFingerprint(),
                old.permittedTools(),old.permittedDataScopes(),old.riskTier(),old.limits(),old.requestingSubject(),old.delegationGrantId());
        return new Fixture(f.row(),binding,f.root(),f.model(),f.parent(),new ResourceTaskAllocation(scope,f.parent().taskId(),contract,f.allocation().modelProfile()));
    }
    Active add(Active first,Fixture f) throws Exception {
        first.arena().add(f);var t=first.arena().store.allocate(f.allocation());
        return new Active(first.arena(),f,t,new ResourceExecutionEntry(f.binding().scope(),1),enter(first.arena(),f));
    }
    static ResourceExecutionFence current() {return entry->{};}
    JdbcResourceModelCalls gate(Active m) {return new JdbcResourceModelCalls(m.arena().redirected,transactions);}
    long count(Active m,String table) throws SQLException {
        assertTrue(Set.of("model_call_reservation","model_budget_account").contains(table));
        try(var c=data.getConnection();var s=c.createStatement();var r=s.executeQuery("select count(*) from "+m.arena().schema+"."+table)){assertTrue(r.next());return r.getLong(1);}
    }
    long held(Active m) throws SQLException {
        try(var c=data.getConnection();var s=c.createStatement();var r=s.executeQuery("select coalesce(sum(held_micros),0) from "+m.arena().schema+".model_call_reservation")){assertTrue(r.next());return r.getLong(1);}
    }
    String invoke(JdbcResourceModelCalls gate,ResourceModelCall call,AtomicInteger calls) {
        return gate.invokeOnce(call,current(),reservation->()->{calls.incrementAndGet();return "answer";});
    }
    @Test void knownFreshCommitPrecedesAuthorizationAndOnlyOneClosureThenHistoricalData() throws Exception {
        try(var m=active(false)) {
            var gate=gate(m);var physical=new AtomicInteger();var authorized=new AtomicInteger();var call=m.call("graph/plan/1");
            assertEquals("answer",gate.invokeOnce(call,current(),r->{
                authorized.incrementAndGet();assertEquals(1,countUnchecked(m,"model_call_reservation"));assertEquals(1000,r.protectedCallBudgetMicros());
                assertEquals(Duration.ofSeconds(10),Duration.between(r.reservedAt(),r.callDeadline())); // explicit floor of profile's123ns.
                return ()->{physical.incrementAndGet();return "answer";};
            }));
            var data=gate.find(call).orElseThrow();assertEquals(1,data.ordinal());assertEquals(2,data.callsRemaining());
            assertEquals(4000,data.allocationRemainingAfterMicros());assertEquals(1,authorized.get());assertEquals(1,physical.get());
            assertThrows(ModelCallAlreadyReservedException.class,()->invoke(gate,call,physical));
            assertEquals(data,new JdbcResourceModelCalls(m.arena().redirected,transactions).find(call).orElseThrow());
            assertEquals(1,count(m,"model_call_reservation"));assertEquals(1000,held(m));assertEquals(1,physical.get());
        }
    }
    long countUnchecked(Active m,String table) {try{return count(m,table);}catch(SQLException e){throw new AssertionError(e);}}
    @Test void concurrentSameNodeHasOneCallbackAndOneRetainedHold() throws Exception {
        try(var m=active(false)) {
            var gate=gate(m);var calls=new AtomicInteger();var ready=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);
            try {
                List<Future<Boolean>> results=new ArrayList<>();
                for(int i=0;i<6;i++)results.add(pool.submit(()->{ready.await();try{invoke(gate,m.call("plan/1"),calls);return true;}catch(ModelCallAlreadyReservedException prior){return false;}}));
                ready.countDown();int success=0;for(var f:results)if(f.get(20,TimeUnit.SECONDS))success++;assertEquals(1,success);
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            assertEquals(1,calls.get());assertEquals(1,count(m,"model_call_reservation"));assertEquals(1000,held(m));
        }
    }
    @Test void concurrentDistinctNodesCannotExceedTheOriginalCallCount() throws Exception {
        try(var m=active(false)) {
            var gate=gate(m);var calls=new AtomicInteger();var ready=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(5);
            try {
                List<Future<Boolean>> results=new ArrayList<>();
                for(int i=0;i<5;i++){final int n=i;results.add(pool.submit(()->{ready.await();try{invoke(gate,m.call("node/"+n),calls);return true;}catch(ModelBudgetExhaustedException limit){return false;}}));}
                ready.countDown();int success=0;for(var f:results)if(f.get(20,TimeUnit.SECONDS))success++;assertEquals(3,success);
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            assertEquals(3,calls.get());assertEquals(3,count(m,"model_call_reservation"));assertEquals(3000,held(m));
        }
    }
    @Test void leafCostCapProtectsTheLastAffordableCallAndCannotReset() throws Exception {
        try(var m=active(false,p->profile(p,3,2500,p.maxCallTime()),true)) {
            var gate=gate(m);var calls=new AtomicInteger();invoke(gate,m.call("one"),calls);
            gate.invokeOnce(m.call("two"),current(),r->{assertEquals(0,r.allocationRemainingAfterMicros());assertEquals(2500,r.protectedCallBudgetMicros());return ()->{calls.incrementAndGet();return "last";};});
            assertThrows(ModelBudgetExhaustedException.class,()->invoke(gate,m.call("three"),calls));
            assertEquals(2,calls.get());assertEquals(5000,held(m));
        }
    }
    @Test void siblingAllocationsShareTheirActualParentModelBudget() throws Exception {
        try(var first=active(true,p->profile(p,3,5000,p.maxCallTime()),true)) {
            var second=add(first,sibling(first.fixture(),2));var third=add(first,sibling(first.fixture(),3));
            var calls=new AtomicInteger();invoke(gate(first),first.call("plan"),calls);invoke(gate(second),second.call("plan"),calls);
            assertThrows(ModelBudgetExhaustedException.class,()->invoke(gate(third),third.call("plan"),calls));
            assertEquals(2,calls.get());assertEquals(10000,held(first));assertEquals(1,count(first,"model_budget_account"));
            var a=gate(first).find(first.call("plan")).orElseThrow();var b=gate(second).find(second.call("plan")).orElseThrow();
            assertEquals(a.account(),b.account());assertEquals(ModelBudgetAccount.AnchorKind.TASK,a.account().anchorKind());assertEquals(first.fixture().parent().taskId().value(),a.account().anchorId());
        }
    }
    @Test void lostActualCommitReplyCallsNoAuthorizationOrProviderAndNeverRetriesTheHeldNode() throws Exception {
        try(var m=active(false)) {
            var g=gate(m);var authorized=new AtomicInteger();var calls=new AtomicInteger();m.arena().loseCommit.set(true);
            refused("08006",()->g.invokeOnce(m.call("plan"),current(),r->{authorized.incrementAndGet();return ()->{calls.incrementAndGet();return "answer";};}));
            assertEquals(0,authorized.get());assertEquals(0,calls.get());assertTrue(g.find(m.call("plan")).isPresent());
            assertThrows(ModelCallAlreadyReservedException.class,()->invoke(g,m.call("plan"),calls));assertEquals(0,calls.get());assertEquals(1000,held(m));
        }
    }
    @Test void changedPromptTokensEpochOrExecutorCannotCreateAnotherNodeBudget() throws Exception {
        try(var m=active(false)) {
            var g=gate(m);var calls=new AtomicInteger();var original=m.call("plan");invoke(g,original,calls);
            for(var changed:List.of(m.call("plan","changed prompt",100),m.call("plan","fictional private prompt",99),
                    new ResourceModelCall(new ResourceExecutionEntry(m.entry().scope(),2),m.executor(),"plan",original.requestFingerprint(),100),
                    new ResourceModelCall(m.entry(),UUID.randomUUID(),"plan",original.requestFingerprint(),100)))
                assertThrows(IllegalStateException.class,()->invoke(g,changed,calls));
            assertEquals(1,calls.get());assertEquals(1,count(m,"model_call_reservation"));
        }
    }
    @Test void authorizationDenialAndErrorRetainAllowanceWithoutCallingProvider() throws Exception {
        try(var m=active(false)) {
            var g=gate(m);var calls=new AtomicInteger();
            for(String reason:List.of("DENY","ERROR"))assertThrows(SecurityException.class,()->g.invokeOnce(m.call(reason),current(),r->{throw new SecurityException(reason);}));
            assertEquals(0,calls.get());assertEquals(2000,held(m));assertEquals(2,count(m,"model_call_reservation"));
        }
    }
    @Test void providerFailureRetainsFullHoldAndCannotInvokeAgain() throws Exception {
        try(var m=active(false)) {
            var g=gate(m);var calls=new AtomicInteger();
            assertThrows(IllegalArgumentException.class,()->g.invokeOnce(m.call("plan"),current(),r->()->{calls.incrementAndGet();throw new IllegalArgumentException("provider unknown");}));
            assertThrows(ModelCallAlreadyReservedException.class,()->invoke(g,m.call("plan"),calls));assertEquals(1,calls.get());assertEquals(1000,held(m));
        }
    }
    @Test void absentExecutionEntryOrWrongOwnerNeverCallsTheCallbacks() throws Exception {
        try(var m=active(false,UnaryOperator.identity(),false)) {
            var calls=new AtomicInteger();refused("23514",()->invoke(gate(m),m.call("plan"),calls));assertEquals(0,calls.get());assertEquals(0,count(m,"model_budget_account"));
        }
        try(var m=active(false)) {
            var calls=new AtomicInteger();var call=m.call("plan");var wrong=new ResourceModelCall(call.entry(),UUID.randomUUID(),call.nodeOccurrence(),call.requestFingerprint(),100);
            refused("23514",()->invoke(gate(m),wrong,calls));assertEquals(0,calls.get());assertEquals(0,count(m,"model_call_reservation"));
        }
    }
    @Test void registeredOutputTokenBoundCannotBeWidenedByAConstructedIntent() throws Exception {
        try(var m=active(false)) {
            var good=m.call("plan");var bad=new ResourceModelCall(good.entry(),good.executor(),good.nodeOccurrence(),good.requestFingerprint(),2049);var calls=new AtomicInteger();
            assertThrows(IllegalStateException.class,()->invoke(gate(m),bad,calls));assertEquals(0,calls.get());assertEquals(0,count(m,"model_call_reservation"));
        }
    }
    @Test void registryClosureAndFullOwnerChangeAfterAuthorizationMakeNoProviderCall() throws Exception {
        for(String change:List.of("status='CANCELLED'","owner_kind='SERVICE'"))try(var m=active(false)) {
            var calls=new AtomicInteger();refused("23514",()->gate(m).invokeOnce(m.call("plan"),current(),r->{
                try{m.arena().change("update "+m.arena().schema+".instance set "+change);}catch(SQLException e){throw new AssertionError(e);}
                return ()->{calls.incrementAndGet();return "private";};}));
            assertEquals(0,calls.get());assertEquals(1000,held(m));
        }
    }
    @Test void realGrantRevocationAfterAuthorizationOrProviderRefusesNewWorkOrOutput() throws Exception {
        try(var m=active(false)) {
            var calls=new AtomicInteger();refused("23514",()->gate(m).invokeOnce(m.call("plan"),current(),r->{
                grants.revoke(m.fixture().model().id(),ALICE,"test",null);return ()->{calls.incrementAndGet();return "private";};}));assertEquals(0,calls.get());assertEquals(1000,held(m));
        }
        try(var m=active(false)) {
            var calls=new AtomicInteger();refused("23514",()->gate(m).invokeOnce(m.call("plan"),current(),r->()->{
                calls.incrementAndGet();grants.revoke(m.fixture().model().id(),ALICE,"test",null);return "private";}));assertEquals(1,calls.get());assertEquals(1000,held(m));
        }
    }
    @Test void actualParentWaitAfterAuthorizationStopsTheModelNode() throws Exception {
        try(var m=active(true)) {
            var calls=new AtomicInteger();refused("23514",()->gate(m).invokeOnce(m.call("plan"),current(),r->{
                try{m.arena().change("update "+m.arena().schema+".agent_task set status='WAITING_FOR_HUMAN'");}catch(SQLException e){throw new AssertionError(e);}
                return ()->{calls.incrementAndGet();return "private";};}));assertEquals(0,calls.get());assertEquals(1000,held(m));
        }
    }
    @Test void reservationDeadlineAfterBlockingAuthorizationOrProviderIsNeverRenewed() throws Exception {
        try(var m=active(false,p->profile(p,3,1000,Duration.ofSeconds(1)),true)) {
            var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->gate(m).invokeOnce(m.call("plan"),current(),r->{
                sleep(1200);return ()->{calls.incrementAndGet();return "private";};}));assertEquals(0,calls.get());assertEquals(1000,held(m));
        }
        try(var m=active(false,p->profile(p,3,1000,Duration.ofSeconds(1)),true)) {
            var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->gate(m).invokeOnce(m.call("plan"),current(),r->()->{
                calls.incrementAndGet();sleep(1200);return "private";}));assertEquals(1,calls.get());assertEquals(1000,held(m));
        }
    }
    static void sleep(long millis) {try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void livePrivateFenceAfterAuthorizationOrProviderPreventsWorkOrOutput() throws Exception {
        for(boolean duringProvider:new boolean[]{false,true})try(var m=active(false)) {
            var live=new AtomicBoolean(true);var calls=new AtomicInteger();ResourceExecutionFence fence=e->{assertEquals(m.entry(),e);if(!live.get())throw new SecurityException("closed private authority");};
            assertThrows(SecurityException.class,()->gate(m).invokeOnce(m.call("plan"),fence,r->{
                if(!duringProvider)live.set(false);
                return ()->{calls.incrementAndGet();live.set(false);return "private";};}));
            assertEquals(duringProvider?1:0,calls.get());assertEquals(1000,held(m));
        }
    }
    @Test void interruptionBeforeReservationAndAfterCallbacksPreservesFlagAndHeldFacts() throws Exception {
        try(var m=active(false)) {
            var calls=new AtomicInteger();Thread.currentThread().interrupt();
            try{assertThrows(CancellationException.class,()->invoke(gate(m),m.call("before"),calls));assertTrue(Thread.currentThread().isInterrupted());}
            finally{Thread.interrupted();}
            assertEquals(0,count(m,"model_call_reservation"));
            for(boolean duringProvider:new boolean[]{false,true}) {
                try{assertThrows(CancellationException.class,()->gate(m).invokeOnce(m.call("interrupt/"+duringProvider),current(),r->{
                    if(!duringProvider)Thread.currentThread().interrupt();return ()->{calls.incrementAndGet();Thread.currentThread().interrupt();return "private";};}));assertTrue(Thread.currentThread().isInterrupted());}
                finally{Thread.interrupted();}
            }
            assertEquals(1,calls.get());assertEquals(2000,held(m));
        }
    }
    @Test void callerJtaIsSuspendedAcrossEveryHookAndSingleConnectionAdmissionAllowsCallbackIo() throws Exception {
        try(var m=active(false)) {
            DataSource one=single(m.arena().redirected);var g=new JdbcResourceModelCalls(one,transactions);var calls=new AtomicInteger();callerTransaction.begin();
            try {
                ResourceExecutionFence fence=e->{assertEquals(jakarta.transaction.Status.STATUS_NO_TRANSACTION,status());};
                assertEquals("answer",g.invokeOnce(m.call("plan"),fence,r->{probe(one);return ()->{assertEquals(jakarta.transaction.Status.STATUS_NO_TRANSACTION,status());probe(one);calls.incrementAndGet();return "answer";};}));
                assertEquals(jakarta.transaction.Status.STATUS_ACTIVE,callerTransaction.getStatus());
            } finally{callerTransaction.rollback();}
            assertEquals(1,calls.get());assertTrue(g.find(m.call("plan")).isPresent());
        }
    }
    int status(){try{return transactions.getStatus();}catch(Exception e){throw new AssertionError(e);}}
    static void probe(DataSource d){try(var c=d.getConnection();var q=c.createStatement();var r=q.executeQuery("select 1")){assertTrue(r.next());}catch(SQLException e){throw new AssertionError(e);}}
    static DataSource single(DataSource original) {
        var permit=new Semaphore(1);
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            if(!m.getName().equals("getConnection"))return invoke(original,m,a);
            if(!permit.tryAcquire(2,TimeUnit.SECONDS))throw new SQLException("test: one-connection admission timed out","08006");
            Connection c;
            try{c=(Connection)invoke(original,m,a);}catch(Throwable t){permit.release();throw t;}
            var closed=new AtomicBoolean();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                if(cm.getName().equals("close")){try{return invoke(c,cm,ca);}finally{if(closed.compareAndSet(false,true))permit.release();}}
                return invoke(c,cm,ca);
            });
        });
    }
    @Test void suspendedCallerRegistryLockTimesOutWithoutClosingTheCallerOrInvokingProvider() throws Exception {
        try(var m=active(false)) {
            var calls=new AtomicInteger();callerTransaction.begin();
            try {
                try(var c=data.getConnection()){sql(c,"update "+m.arena().schema+".instance set version=version");}
                refused("55P03",()->invoke(gate(m),m.call("plan"),calls));
                assertEquals(jakarta.transaction.Status.STATUS_ACTIVE,callerTransaction.getStatus());
            } finally{callerTransaction.rollback();}
            assertEquals(0,calls.get());assertEquals(0,count(m,"model_call_reservation"));
        }
    }
    @Test void lostCallerResumeAfterProviderKeepsHoldAndCannotRunAgain() throws Exception {
        try(var m=active(false)) {
            var calls=new AtomicInteger();var once=new AtomicBoolean(true);
            var broken=(TransactionManager)Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),new Class<?>[]{TransactionManager.class},(p,method,args)->{
                Object value=invoke(transactions,method,args);if(method.getName().equals("resume")&&once.compareAndSet(true,false))throw new jakarta.transaction.SystemException("test resume reply lost");return value;});
            callerTransaction.begin();try{assertThrows(StoreUnavailableException.class,()->invoke(new JdbcResourceModelCalls(m.arena().redirected,broken),m.call("plan"),calls));}
            finally{callerTransaction.rollback();}
            assertEquals(1,calls.get());assertEquals(1000,held(m));assertThrows(ModelCallAlreadyReservedException.class,()->invoke(gate(m),m.call("plan"),calls));assertEquals(1,calls.get());
        }
    }
    @Test void malformedOrMissingBudgetMetadataRefusesWithoutAReplacementReservation() throws Exception {
        try(var m=active(false)) {
            var g=gate(m);var calls=new AtomicInteger();invoke(g,m.call("plan"),calls);
            m.arena().change("alter table "+m.arena().schema+".model_call_reservation rename to temporarily_missing_reservations");
            try{refused("42P01",()->g.find(m.call("plan")));refused("42P01",()->invoke(g,m.call("plan"),calls));}
            finally{m.arena().change("alter table "+m.arena().schema+".temporarily_missing_reservations rename to model_call_reservation");}
            m.arena().change("alter table "+m.arena().schema+".model_call_reservation disable trigger user");
            try{m.arena().change("update "+m.arena().schema+".model_call_reservation set held_micros=1");}
            finally{m.arena().change("alter table "+m.arena().schema+".model_call_reservation enable trigger user");}
            assertThrows(RuntimeException.class,()->g.find(m.call("plan")));refused("23514",()->invoke(g,m.call("draft"),calls));assertEquals(1,calls.get());assertEquals(1,count(m,"model_call_reservation"));
        }
    }
    @Test void corruptedApprovedProfileAfterAuthorizationDoesNotReleaseTheProviderClosure() throws Exception {
        try(var m=active(false)) {
            var calls=new AtomicInteger();refused("23514",()->gate(m).invokeOnce(m.call("plan"),current(),r->{
                try{m.arena().change("alter table "+m.arena().schema+".resource_task_allocation disable trigger user");
                    try{m.arena().change("update "+m.arena().schema+".resource_task_allocation set model_profile=model_profile||'{\"unapproved\":true}'::jsonb");}
                    finally{m.arena().change("alter table "+m.arena().schema+".resource_task_allocation enable trigger user");}}
                catch(SQLException e){throw new AssertionError(e);}return ()->{calls.incrementAndGet();return "private";};}));assertEquals(0,calls.get());assertEquals(1,count(m,"model_call_reservation"));
        }
    }
    @Test void historicalReadAfterClosureDoesNotRenewDeadlineOrReturnPermission() throws Exception {
        try(var m=active(false)) {
            var g=gate(m);var calls=new AtomicInteger();var call=m.call("plan");invoke(g,call,calls);var original=g.find(call).orElseThrow();
            grants.revoke(m.fixture().root().id(),ALICE,"test",null);m.arena().change("update "+m.arena().schema+".instance set status='COMPLETED'");
            assertEquals(original,g.find(call).orElseThrow());assertThrows(ModelCallAlreadyReservedException.class,()->invoke(g,call,calls));assertEquals(1,calls.get());assertEquals(1000,held(m));
        }
    }
    static void raw(Connection c,Active m,List<ResourceModelCall> calls,boolean forgeCost) throws SQLException {
        var allocated=m.task().allocation();String cols="reservation_key,call_key,entry_key,executor,node_occurrence,request_fingerprint,max_output_tokens,intent_fingerprint,allocation_key,allocation_fingerprint,account_key"+(forgeCost?",held_micros":"");
        String row="("+String.join(",",Collections.nCopies(forgeCost?12:11,"?"))+")";
        try(var q=c.prepareStatement("insert into "+m.arena().schema+".model_call_reservation("+cols+") values "+String.join(",",Collections.nCopies(calls.size(),row)))) {
            int n=0;for(var call:calls){q.setString(++n,call.key());q.setString(++n,call.entry().callKey());q.setString(++n,call.entry().fingerprint());q.setObject(++n,call.executor());
                q.setString(++n,call.nodeOccurrence());q.setString(++n,call.requestFingerprint());q.setInt(++n,call.maxOutputTokens());q.setString(++n,call.fingerprint());q.setString(++n,allocated.key());
                q.setString(++n,allocated.fingerprint());q.setString(++n,ModelBudgetAccount.keyFor(allocated));if(forgeCost)q.setLong(++n,1);}
            q.executeUpdate();
        }
    }
    @Test void directSqlCannotForgeAChargeOrOverrunABatchedCallCountAndFactsAreRetained() throws Exception {
        try(var m=active(false);var c=data.getConnection()) {
            var calls=new AtomicInteger();invoke(gate(m),m.call("one"),calls);
            var forged=assertThrows(SQLException.class,()->raw(c,m,List.of(m.call("two")),true));assertEquals("23514",forged.getSQLState());assertEquals(1,count(m,"model_call_reservation"));
            var exhausted=assertThrows(SQLException.class,()->raw(c,m,List.of(m.call("two"),m.call("three"),m.call("four")),false));assertEquals("OA001",exhausted.getSQLState());assertEquals(1,count(m,"model_call_reservation"));
            for(String table:List.of("model_budget_account","model_call_reservation"))for(String operation:List.of("delete from ","truncate ")) {
                var denied=assertThrows(SQLException.class,()->sql(c,operation+m.arena().schema+"."+table+(operation.startsWith("truncate")?" cascade":"")));assertEquals("42501",denied.getSQLState());
            }
            invoke(gate(m),m.call("two"),calls);assertEquals(2,calls.get());assertEquals(2000,held(m));
        }
    }
}
