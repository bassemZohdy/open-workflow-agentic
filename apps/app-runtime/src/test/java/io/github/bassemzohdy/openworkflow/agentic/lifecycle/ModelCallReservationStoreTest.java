package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.wrapping;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.invoke;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Full V77/private proposed V78+V79. Callbacks are fixture closures, not physical provider/approval proof. */
@QuarkusTest
class ModelCallReservationStoreTest {
    @Inject TransactionManager manager;
    @Inject Instance<ResourceModelCalls> defaults;
    ResourceTaskAllocationMigrationTest support;
    DataSource data;
    Fixture f;
    ResourceTaskAllocation a;
    AllocatedResourceTask task;
    UUID executor;
    JdbcResourceModelCalls calls;
    @BeforeEach void create() throws Exception {
        support=new ResourceTaskAllocationMigrationTest();support.create();support.apply();data=support.db.owner;
        apply79(data);f=fixture(support.db,"a".repeat(64));a=support.candidate(f);calls=new JdbcResourceModelCalls(data,manager);
    }
    @AfterEach void drop() throws Exception {if(support!=null)support.close();}
    static String proposal79() throws Exception {
        try(var in=ModelCallReservationStoreTest.class.getClassLoader().getResourceAsStream("db/proposed/V79__model_call_reservations.sql")) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static void apply79(DataSource ds) throws Exception {
        try(var c=ds.getConnection()) {c.setAutoCommit(false);
            try(var s=c.createStatement()){s.setQueryTimeout(10);s.execute(proposal79());c.commit();}
            catch(Exception|Error failure){c.rollback();throw failure;}
        }
    }
    void enter(ResourceTaskAllocation allocation,Fixture source) throws Exception {
        a=allocation;f=source;task=new JdbcResourceTaskAllocations(data,manager).allocate(a);support.entry(f);
        try(var c=data.getConnection();var q=c.prepareStatement("select entering_executor from task.resource_execution where call_key=?")) {
            q.setString(1,a.scope().key());try(var r=q.executeQuery()){assertTrue(r.next());executor=r.getObject(1,UUID.class);}
        }
    }
    void enter() throws Exception {enter(a,f);}
    ResourceModelCall node(String name) {
        return ResourceModelCall.of(task,new ResourceExecutionEntry(a.scope(),1),executor,name,
            new ModelCall(a.contract().id(),a.modelProfile().modelId(),"private fixture prompt 研究𝄞",100));
    }
    long count(String table) throws SQLException {return ResourceExecutionMigrationTest.count(data,"select count(*) from task."+table);}
    long held() throws Exception {
        long rows=Long.parseLong(scalar("select coalesce(sum(held_micros),0)::text from task.model_call_reservation"));
        long summary=Long.parseLong(scalar("select coalesce(sum(reserved_micros),0)::text from task.model_budget_state"));
        long reservations=Long.parseLong(scalar("select count(*)::text from task.model_call_reservation"));
        long summarized=Long.parseLong(scalar("select coalesce(sum(reservation_count),0)::text from task.model_budget_state"));
        assertEquals(rows,summary,"trigger-maintained account summary must match retained reservations");
        assertEquals(reservations,summarized,"trigger-maintained reservation count must match retained rows");
        return rows;
    }
    String scalar(String query) throws Exception {try(var c=data.getConnection()){return ResourceTaskAllocationMigrationTest.scalar(c,query);}}
    static ResourceExecutionFence current(){return entry->{};}
    static String answer(JdbcResourceModelCalls gate,ResourceModelCall node,AtomicInteger effects) {
        return gate.invokeOnce(node,current(),r->()->{effects.incrementAndGet();return "private fixture answer";});
    }
    static ResourceTaskAllocation profile(ResourceTaskAllocation a,int maxCalls,long ceiling,Duration time) {
        var p=a.modelProfile();return new ResourceTaskAllocation(a.scope(),a.parentTaskId(),a.contract(),
            new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),p.agent(),p.modelId(),maxCalls,ceiling,time,p.maxOutputTokens()));
    }
    void corrupt(String query) throws Exception {
        Transactions.inTransaction(data,c->{try(var s=c.createStatement()){s.execute("set local session_replication_role=replica");s.executeUpdate(query);}return null;});
    }
    @Test void knownFreshCommittedHoldPrecedesOneAuthorizationAndClosureAndHistoricalReadsNeverReenter() throws Exception {
        enter();assertTrue(defaults.isUnsatisfied());var effects=new AtomicInteger();var authorization=new AtomicInteger();var n=node("graph/plan/1");
        assertEquals("private fixture answer",calls.invokeOnce(n,current(),r->{
            authorization.incrementAndGet();assertEquals(1,countUnchecked("model_call_reservation"));assertEquals(1000,r.protectedCallBudgetMicros());
            assertEquals(4000,r.allocationRemainingAfterMicros());assertEquals(2,r.callsRemaining());
            return ()->{effects.incrementAndGet();return "private fixture answer";};
        }));
        var retained=calls.find(n).orElseThrow();assertEquals(1,retained.ordinal());
        assertEquals(Duration.ofSeconds(10),Duration.between(retained.reservedAt(),retained.callDeadline()));
        assertEquals(retained,new JdbcResourceModelCalls(data,manager).find(n).orElseThrow());
        assertThrows(ModelCallAlreadyReservedException.class,()->answer(calls,n,effects));
        assertEquals(1,effects.get());assertEquals(1,authorization.get());assertEquals(1000,held());
        assertEquals(0,support.db.flyway(null).info().pending().length);
        assertEquals("0",scalar("select count(*)::text from public.flyway_schema_history where version in ('78','79')"));
    }
    long countUnchecked(String table) {try{return count(table);}catch(Exception e){throw new AssertionError(e);}}
    @Test void changedRequestEpochExecutorOrOutputLimitCannotMintAnotherHoldAtTheSameNode() throws Exception {
        enter();var n=node("graph/plan/1");var effects=new AtomicInteger();answer(calls,n,effects);
        for(var changed:List.of(new ResourceModelCall(n.entry(),n.executor(),n.nodeOccurrence(),"d".repeat(64),n.maxOutputTokens()),
            new ResourceModelCall(new ResourceExecutionEntry(a.scope(),2),n.executor(),n.nodeOccurrence(),n.requestFingerprint(),n.maxOutputTokens()),
            new ResourceModelCall(n.entry(),UUID.randomUUID(),n.nodeOccurrence(),n.requestFingerprint(),n.maxOutputTokens()),
            new ResourceModelCall(n.entry(),n.executor(),n.nodeOccurrence(),n.requestFingerprint(),99))) {
            assertEquals(n.key(),changed.key());assertThrows(IllegalStateException.class,()->calls.find(changed));
            assertThrows(IllegalStateException.class,()->answer(calls,changed,effects));
        }
        assertEquals(1,effects.get());assertEquals(1,count("model_call_reservation"));assertEquals(1000,held());
    }
    @Test void concurrentSameNodeAllowsExactlyOneClosureAndOneRetainedHold() throws Exception {
        enter();var n=node("graph/plan/1");var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);var effects=new AtomicInteger();
        try {var results=new ArrayList<Future<Boolean>>();for(int i=0;i<6;i++)results.add(pool.submit(()->{start.await();
            try{answer(new JdbcResourceModelCalls(data,manager),n,effects);return true;}catch(ModelCallAlreadyReservedException expected){return false;}}));
            start.countDown();int accepted=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,effects.get());assertEquals(1000,held());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void callCountAndLeafCeilingBothUseRetainedFactsRatherThanCallerCounters() throws Exception {
        enter();var effects=new AtomicInteger();for(int i=0;i<3;i++)answer(calls,node("graph/node/"+i),effects);
        assertThrows(ModelBudgetExhaustedException.class,()->answer(calls,node("graph/node/3"),effects));
        assertEquals(3,effects.get());assertEquals(3000,held());assertEquals(3,count("model_call_reservation"));
    }
    @Test void leafMoneyRunsOutBeforeCallCountWithoutPartialHoldOrCallback() throws Exception {
        a=profile(a,3,2000,Duration.ofSeconds(10));enter();var effects=new AtomicInteger();
        answer(calls,node("graph/0"),effects);answer(calls,node("graph/1"),effects);
        assertThrows(ModelBudgetExhaustedException.class,()->answer(calls,node("graph/2"),effects));
        assertEquals(2,effects.get());assertEquals(4000,held());assertEquals(2,count("model_call_reservation"));
    }
    @Test void authorizationDenialAndUnknownProviderOutcomeRetainFullHoldsWithoutRetryOrRefund() throws Exception {
        enter();var effects=new AtomicInteger();var denied=node("graph/denied");var unknown=node("graph/unknown");
        var denial=new IllegalStateException("fixture policy denial");
        assertSame(denial,assertThrows(IllegalStateException.class,()->calls.invokeOnce(denied,current(),r->{throw denial;})));
        assertThrows(IllegalStateException.class,()->calls.invokeOnce(unknown,current(),r->()->{effects.incrementAndGet();throw new IllegalStateException("lost response");}));
        assertTrue(calls.find(denied).isPresent());assertTrue(calls.find(unknown).isPresent());
        assertThrows(ModelCallAlreadyReservedException.class,()->answer(calls,unknown,effects));
        assertEquals(1,effects.get());assertEquals(2000,held());
    }
    @Test void identityLostDuringBlockingAuthorizationRefusesBeforeTheClosureAndRetainsTheHold() throws Exception {
        enter();var live=new AtomicBoolean(true);var effects=new AtomicInteger();var n=node("graph/plan");
        ResourceExecutionFence fence=e->{if(!live.get())throw new IllegalStateException("fixture identity ended");};
        assertThrows(IllegalStateException.class,()->calls.invokeOnce(n,fence,r->{live.set(false);return ()->{effects.incrementAndGet();return "private";};}));
        assertEquals(0,effects.get());assertEquals(1000,held());assertTrue(calls.find(n).isPresent());
    }
    @Test void registryCancellationDuringAuthorizationRefusesBeforeTheClosure() throws Exception {
        enter();var effects=new AtomicInteger();var n=node("graph/plan");
        assertThrows(StoreUnavailableException.class,()->calls.invokeOnce(n,current(),r->{
            try{execute(data,"update task.instance set status='CANCEL_REQUESTED'");}catch(SQLException e){throw new AssertionError(e);}
            return ()->{effects.incrementAndGet();return "private";};}));
        assertEquals(0,effects.get());assertEquals(1000,held());assertTrue(calls.find(n).isPresent());
    }
    DelegationGrants grants(){var clock=Clock.systemUTC();return new DelegationGrants(new DelegationGrantStore(data),
        new IdentityAudit(e->{},clock,UlidGenerator.shared()),clock,UlidGenerator.shared(),Duration.ofHours(2));}
    @Test void actualRevocationDuringProviderWorkWithholdsOutputAndDoesNotUndoTheCompletedClosure() throws Exception {
        enter();var effects=new AtomicInteger();var n=node("graph/plan");
        assertThrows(StoreUnavailableException.class,()->calls.invokeOnce(n,current(),r->()->{effects.incrementAndGet();
            grants().revoke(a.contract().delegationGrantId(),a.scope().owner(),"fixture late revoke",null);return "private must be withheld";}));
        assertEquals(1,effects.get());assertEquals(1000,held());assertTrue(calls.find(n).isPresent());
    }
    @Test void actualCommittedHoldWithLostReplyNeverCallsPreparationOrClosureAndReopenFindsTheSameHold() throws Exception {
        enter();var commits=new AtomicInteger();var effects=new AtomicInteger();var authorizations=new AtomicInteger();
        var lost=wrapping(data,c->(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
            if(m.getName().equals("commit")&&commits.incrementAndGet()==1){c.commit();throw new SQLException("fixture committed reply lost");}
            return invoke(m,c,args);
        }));
        var n=node("graph/plan");assertThrows(StoreUnavailableException.class,()->new JdbcResourceModelCalls(lost,manager).invokeOnce(n,current(),r->{
            authorizations.incrementAndGet();return ()->{effects.incrementAndGet();return "private";};}));
        var retained=calls.find(n).orElseThrow();assertEquals(1000,retained.heldMicros());assertEquals(0,effects.get());assertEquals(0,authorizations.get());
        assertThrows(ModelCallAlreadyReservedException.class,()->answer(calls,n,effects));assertEquals(retained,calls.find(n).orElseThrow());
    }
    @Test void realAmbientRollbackCannotEraseTheOwnedReservationAndCallbacksRunWithoutCallerJta() throws Exception {
        enter();var n=node("graph/plan");manager.begin();var caller=manager.getTransaction();
        try {calls.invokeOnce(n,current(),r->{try{assertNull(manager.getTransaction());}catch(SystemException e){throw new AssertionError(e);}
            return ()->{try{assertNull(manager.getTransaction());}catch(SystemException e){throw new AssertionError(e);}return "private";};});
            assertSame(caller,manager.getTransaction());
        } finally {manager.rollback();}
        assertTrue(calls.find(n).isPresent());assertEquals(1000,held());
    }
    @Test void corruptReservationOrMissingEntryIsRefusalRatherThanAbsenceOrAnotherFreeHold() throws Exception {
        enter();var n=node("graph/plan");answer(calls,n,new AtomicInteger());
        corrupt("update task.model_call_reservation set fingerprint=repeat('0',64)");assertThrows(IllegalStateException.class,()->calls.find(n));
        var actual=scalar("select task.mr_fingerprint_v1(r.reservation_key,r.intent_fingerprint,a.fingerprint,a.created_at,a.deadline,b.account_key,b.fingerprint,r.held_micros,r.allocation_remaining_after,r.account_remaining_after,r.ordinal,r.calls_remaining,r.reserved_at,r.call_deadline) from task.model_call_reservation r join task.resource_task_allocation a using(allocation_key) join task.model_budget_account b using(account_key)");
        corrupt("update task.model_call_reservation set fingerprint='"+actual+"'");assertTrue(calls.find(n).isPresent());
        corrupt("delete from task.resource_execution");assertThrows(IllegalStateException.class,()->calls.find(n));assertEquals(1000,held());
    }
    @Test void originalAllocationAndNodeDeadlineCrossedOnTheDatabaseClockNeverRenewOrAuthorizeReadback() throws Exception {
        var t=a.contract();var shortTask=new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),
            new ExecutionLimits(t.limits().maxIterations(),Duration.ofSeconds(2),t.limits().maxCostMicros()),t.requestingSubject(),t.delegationGrantId());
        a=new ResourceTaskAllocation(a.scope(),null,shortTask,profile(a,3,1000,Duration.ofSeconds(1)).modelProfile());enter();
        var n=node("graph/plan");var effects=new AtomicInteger();answer(calls,n,effects);var original=calls.find(n).orElseThrow();
        long bound=System.nanoTime()+Duration.ofSeconds(6).toNanos();while(databaseNow(data).isBefore(task.deadline())){if(System.nanoTime()>bound)fail("original database deadline not crossed");Thread.sleep(20);}
        assertEquals(original,calls.find(n).orElseThrow());assertThrows(ModelCallAlreadyReservedException.class,()->answer(calls,n,effects));
        assertThrows(StoreUnavailableException.class,()->answer(calls,node("graph/new"),effects));assertEquals(1,effects.get());assertEquals(1000,held());
    }
    @Test void missingExecutionEntryAndWrongExecutorRefuseBeforePreparationOrClosure() throws Exception {
        task=new JdbcResourceTaskAllocations(data,manager).allocate(a);executor=UUID.randomUUID();var effects=new AtomicInteger();
        assertThrows(StoreUnavailableException.class,()->answer(calls,node("graph/plan"),effects));assertEquals(0,count("model_call_reservation"));
        support.entry(f);assertThrows(StoreUnavailableException.class,()->answer(calls,node("graph/plan"),effects));
        assertEquals(0,effects.get());assertEquals(0,count("model_budget_account"));
    }
    @Test void serializableWritesRefuseWithoutDowngradeAndHistoricalSnapshotsRetainStricterSettings() throws Exception {
        enter();var commits=new ArrayList<String>();var strict=wrapping(data,c->{c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var s=c.createStatement()){s.execute("set lock_timeout='100ms';set statement_timeout='500ms'");}
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
                if(m.getName().equals("setTransactionIsolation"))fail("gate must not reset inherited isolation");
                if(m.getName().equals("commit"))commits.add(ResourceTaskAllocationMigrationTest.scalar(c,"select current_setting('transaction_isolation')||':'||current_setting('lock_timeout')||':'||current_setting('statement_timeout')"));
                return invoke(m,c,args);
            });});
        var gate=new JdbcResourceModelCalls(strict,manager);var n=node("graph/plan");var effects=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->answer(gate,n,effects));assertEquals(0,effects.get());assertEquals(0,held());
        answer(calls,n,effects);assertTrue(gate.find(n).isPresent());
        assertFalse(commits.isEmpty());assertTrue(commits.stream().allMatch("serializable:100ms:500ms"::equals));
    }
    @Test void timeoutResetFailureAfterCommitEvictsConnectionWithoutChangingCommittedResult() throws Exception {
        var timeoutSets=new AtomicInteger();var commits=new AtomicInteger();var aborts=new AtomicInteger();
        var resetFails=wrapping(data,c->(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
            if(m.getName().equals("setNetworkTimeout")&&timeoutSets.incrementAndGet()==2)throw new SQLException("injected reset failure");
            if(m.getName().equals("commit"))commits.incrementAndGet();
            if(m.getName().equals("abort"))aborts.incrementAndGet();
            return invoke(m,c,args);
        }));
        assertEquals("durably committed",Transactions.inTransaction(resetFails,c->"durably committed",30_000));
        assertEquals(1,commits.get());assertEquals(2,timeoutSets.get());assertEquals(1,aborts.get());
    }
    @Test void siblingsShareOneActualParentCapRatherThanReceivingFreshIndependentSourceBudgets() throws Exception {
        var old=a.contract();var child=new AgentTaskContract(old.id(),old.goal(),old.permittedTools(),old.permittedDataScopes(),old.riskTier(),
            new ExecutionLimits(5,Duration.ofSeconds(120),1000),old.requestingSubject(),old.delegationGrantId());
        var p=new AgentTaskContract(UlidGenerator.shared().next(),"actual parent cap",old.permittedTools(),old.permittedDataScopes(),RiskTier.MEDIUM,
            new ExecutionLimits(10,Duration.ofSeconds(600),1500),old.requestingSubject(),f.binding().issuingGrantId());
        var pending=new AgentTaskRecord(p.id(),a.scope().tenant(),a.scope().owner(),a.scope().instanceId().value(),
            ThreadId.derive(a.scope().tenant(),a.scope().owner().id(),a.scope().instanceId().value()),p,AgentTaskStatus.PENDING,0);
        var parent=new AgentTaskRecord(pending.taskId(),pending.tenant(),pending.owner(),pending.workflowInstance(),pending.threadId(),
            p,AgentTaskStatus.RUNNING,1);var tasks=new JdbcTaskStore(data);tasks.insert(pending);tasks.replace(parent,0);
        a=new ResourceTaskAllocation(a.scope(),parent.taskId(),child,a.modelProfile());enter();
        var effects=new AtomicInteger();var first=node("graph/plan");answer(calls,first,effects);var original=calls.find(first).orElseThrow();
        var scope=a.scope();var sibling=new ResourceCallScope(scope.tenant(),scope.instanceId(),scope.owner(),scope.applicationId(),scope.engineInstanceId(),
            scope.namespace(),scope.definition(),scope.version(),scope.taskPosition(),2,"root/sibling/2",scope.audience(),scope.operation(),scope.targetFingerprint(),scope.requestFingerprint());
        var expires=databaseNow(data).plusSeconds(120);var jti=UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(data).bind(f.binding().issuer(),jti,new IssuedTokenBindings.Binding(f.binding().issuingGrantId(),scope.tenant(),expires));
        var binding=new ResourceCallBinding(f.binding().issuer(),jti,f.binding().issuingGrantId(),sibling,expires.minusSeconds(121),expires);
        new JdbcResourceCallBindings(data).bind(binding);
        var second=new AgentTaskContract(UlidGenerator.shared().next(),child.goal(),child.permittedTools(),child.permittedDataScopes(),child.riskTier(),child.limits(),child.requestingSubject(),child.delegationGrantId());
        enter(new ResourceTaskAllocation(sibling,parent.taskId(),second,a.modelProfile()),new Fixture(f.row(),binding));
        assertThrows(ModelBudgetExhaustedException.class,()->answer(calls,node("graph/plan"),effects));
        assertEquals(1,effects.get());assertEquals(1,count("model_budget_account"));assertEquals(1000,held());assertEquals(500,original.accountRemainingAfterMicros());
        assertEquals(parent,new JdbcTaskStore(data).find(parent.taskId()).orElseThrow());
    }
    @Test void oneActualConnectionSlotIsReleasedBeforePreparationAndProviderCallbacks() throws Exception {
        enter();var occupied=new AtomicBoolean();
        var single=wrapping(data,c->{
            if(!occupied.compareAndSet(false,true)){c.close();throw new SQLException("single fixture connection already held");}
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
                if(m.getName().equals("close")){try{return invoke(m,c,args);}finally{occupied.set(false);}}
                return invoke(m,c,args);
            });
        });
        var effects=new AtomicInteger();var gate=new JdbcResourceModelCalls(single,manager);
        assertEquals("private",gate.invokeOnce(node("graph/plan"),current(),r->{
            assertFalse(occupied.get());try(var c=single.getConnection()){assertEquals("1",ResourceTaskAllocationMigrationTest.scalar(c,"select 1"));}
            catch(SQLException e){throw new AssertionError(e);}
            return ()->{assertFalse(occupied.get());try(var c=single.getConnection()){assertEquals("1",ResourceTaskAllocationMigrationTest.scalar(c,"select 1"));}
                catch(SQLException e){throw new AssertionError(e);}effects.incrementAndGet();return "private";};
        }));
        assertFalse(occupied.get());assertEquals(1,effects.get());
    }
    @Test void resumeFailureKeepsThePrimaryFailureAndWithholdsSuccessfulOutputAfterACommittedCall() throws Exception {
        enter();var bad=(TransactionManager)Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),new Class[]{TransactionManager.class},(p,m,args)->{
            Object result=invoke(m,manager,args);if(m.getName().equals("resume"))throw new SystemException("fixture resume failed");return result;
        });
        var gate=new JdbcResourceModelCalls(data,bad);var primary=new IllegalStateException("fixture primary refusal");var n=node("graph/denied");
        manager.begin();try {
            var failure=assertThrows(IllegalStateException.class,()->gate.invokeOnce(n,current(),r->{throw primary;}));
            assertSame(primary,failure);assertEquals(1,failure.getSuppressed().length);assertInstanceOf(SystemException.class,failure.getSuppressed()[0]);
            assertEquals(Status.STATUS_ACTIVE,manager.getStatus());
        } finally {manager.rollback();}
        var effects=new AtomicInteger();manager.begin();try {
            assertThrows(StoreUnavailableException.class,()->answer(gate,node("graph/success"),effects));
            assertEquals(Status.STATUS_ACTIVE,manager.getStatus());
        } finally {manager.rollback();}
        assertEquals(1,effects.get());assertTrue(calls.find(n).isPresent());assertTrue(calls.find(node("graph/success")).isPresent());assertEquals(2000,held());
    }
    @Test void concurrentDistinctNodesCannotBothSpendTheSameLeafAllowance() throws Exception {
        a=profile(a,3,3000,Duration.ofSeconds(10));enter();var first=node("graph/first");var second=node("graph/second");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var effects=new AtomicInteger();
        try {var results=new ArrayList<Future<Boolean>>();for(var n:List.of(first,second))results.add(pool.submit(()->{start.await();
            try{answer(new JdbcResourceModelCalls(data,manager),n,effects);return true;}catch(ModelBudgetExhaustedException expected){return false;}}));
            start.countDown();int accepted=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,effects.get());assertEquals(3000,held());assertEquals(1,count("model_call_reservation"));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void historicalExemptExecutionWithoutAllocationCannotObtainBudgetOrInvokeAnyCallback() throws Exception {
        var old=new ResourceTaskAllocationMigrationTest();
        try {old.create();var source=fixture(old.db,"a".repeat(64));old.entry(source);String before=old.history();old.apply();apply79(old.db.owner);
            UUID retained;try(var c=old.db.owner.getConnection();var s=c.createStatement();var r=s.executeQuery("select entering_executor from task.resource_execution")){
                assertTrue(r.next());retained=r.getObject(1,UUID.class);}
            var n=new ResourceModelCall(new ResourceExecutionEntry(source.binding().scope(),1),retained,"graph/plan","d".repeat(64),100);
            var effects=new AtomicInteger();var gate=new JdbcResourceModelCalls(old.db.owner,manager);
            assertThrows(IllegalStateException.class,()->answer(gate,n,effects));assertEquals(0,effects.get());assertEquals(before,old.history());
            assertEquals(1,ResourceExecutionMigrationTest.count(old.db.owner,"select count(*) from task.resource_execution_allocation_exemption"));
            assertEquals(0,ResourceExecutionMigrationTest.count(old.db.owner,"select count(*) from task.model_call_reservation"));
            assertEquals(0,ResourceExecutionMigrationTest.count(old.db.owner,"select count(*) from task.model_budget_account"));
        } finally {old.close();}
    }
    @Test void accountingRowsCannotBeUpdatedDeletedOrTruncatedAndFailedMutationDoesNotReleaseHolds() throws Exception {
        enter();var n=node("graph/plan");answer(calls,n,new AtomicInteger());var original=calls.find(n).orElseThrow();
        for(var table:List.of("model_budget_account","model_call_reservation","model_budget_state")) {
            String column=switch(table){case "model_budget_account"->"max_cost_micros";case "model_call_reservation"->"held_micros";default->"reserved_micros";};
            for(var sql:List.of("update task."+table+" set "+column+"="+column,"delete from task."+table,"truncate task."+table+" cascade")) {
                assertEquals("42501",assertThrows(SQLException.class,()->execute(data,sql)).getSQLState());
                assertEquals(1,count("model_budget_account"));assertEquals(1,count("model_call_reservation"));assertEquals(1,count("model_budget_state"));
                assertEquals(1000,held());assertEquals(original,calls.find(n).orElseThrow());
            }
        }
    }
    @Test void databaseClockCrossingTheOriginalNodeDeadlineWithholdsCompletedCallbackOutput() throws Exception {
        a=profile(a,3,1000,Duration.ofSeconds(1));enter();var effects=new AtomicInteger();var n=node("graph/plan");
        assertThrows(IllegalStateException.class,()->calls.invokeOnce(n,current(),r->()->{
            effects.incrementAndGet();long bound=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            try {while(databaseNow(data).isBefore(r.callDeadline())){if(System.nanoTime()>bound)fail("original node deadline not crossed");Thread.sleep(20);}}
            catch(Exception e){throw new AssertionError(e);}return "private output withheld";
        }));
        assertEquals(1,effects.get());assertEquals(1000,held());assertTrue(calls.find(n).isPresent());
        assertThrows(ModelCallAlreadyReservedException.class,()->answer(calls,n,effects));assertEquals(1,effects.get());
    }
    void parentWithCap(long cap) throws Exception {
        var old=a.contract();var child=new AgentTaskContract(old.id(),old.goal(),old.permittedTools(),old.permittedDataScopes(),old.riskTier(),
            new ExecutionLimits(5,Duration.ofSeconds(120),1000),old.requestingSubject(),old.delegationGrantId());
        var p=new AgentTaskContract(UlidGenerator.shared().next(),"concurrent parent cap",old.permittedTools(),old.permittedDataScopes(),RiskTier.MEDIUM,
            new ExecutionLimits(10,Duration.ofSeconds(600),cap),old.requestingSubject(),f.binding().issuingGrantId());
        var pending=new AgentTaskRecord(p.id(),a.scope().tenant(),a.scope().owner(),a.scope().instanceId().value(),
            ThreadId.derive(a.scope().tenant(),a.scope().owner().id(),a.scope().instanceId().value()),p,AgentTaskStatus.PENDING,0);
        var running=new AgentTaskRecord(pending.taskId(),pending.tenant(),pending.owner(),pending.workflowInstance(),pending.threadId(),p,AgentTaskStatus.RUNNING,1);
        var tasks=new JdbcTaskStore(data);tasks.insert(pending);tasks.replace(running,0);
        enter(new ResourceTaskAllocation(a.scope(),p.id(),child,a.modelProfile()),f);
    }
    ResourceModelCall siblingNode(int occurrence) throws Exception {
        var scope=a.scope();var sibling=new ResourceCallScope(scope.tenant(),scope.instanceId(),scope.owner(),scope.applicationId(),scope.engineInstanceId(),
            scope.namespace(),scope.definition(),scope.version(),scope.taskPosition(),occurrence,"root/sibling/"+occurrence,
            scope.audience(),scope.operation(),scope.targetFingerprint(),scope.requestFingerprint());
        var expires=databaseNow(data).plusSeconds(120);var jti=UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(data).bind(f.binding().issuer(),jti,new IssuedTokenBindings.Binding(f.binding().issuingGrantId(),scope.tenant(),expires));
        var binding=new ResourceCallBinding(f.binding().issuer(),jti,f.binding().issuingGrantId(),sibling,expires.minusSeconds(121),expires);
        new JdbcResourceCallBindings(data).bind(binding);var old=a.contract();
        var child=new AgentTaskContract(UlidGenerator.shared().next(),old.goal(),old.permittedTools(),old.permittedDataScopes(),old.riskTier(),
            old.limits(),old.requestingSubject(),old.delegationGrantId());
        enter(new ResourceTaskAllocation(sibling,a.parentTaskId(),child,a.modelProfile()),new Fixture(f.row(),binding));return node("graph/plan");
    }
    void concurrentParentRace(int isolation) throws Exception {
        parentWithCap(2500);answer(calls,node("graph/seed"),new AtomicInteger());
        var first=siblingNode(2);var second=siblingNode(3);var effects=new AtomicInteger();
        var pids=new CopyOnWriteArrayList<Integer>();var begun=new CountDownLatch(2);var pool=Executors.newFixedThreadPool(2);
        var configured=wrapping(data,c->{c.setTransactionIsolation(isolation);
            pids.add(Integer.valueOf(ResourceTaskAllocationMigrationTest.scalar(c,"select pg_backend_pid()")));begun.countDown();return c;});
        try(var held=data.getConnection()) {
            held.setAutoCommit(false);try(var q=held.prepareStatement("select id from task.instance where id=? for update")){
                q.setString(1,a.scope().instanceId().value());q.executeQuery().close();}
            var results=new ArrayList<Future<String>>();for(var n:List.of(first,second))results.add(pool.submit(()->{
                try{answer(new JdbcResourceModelCalls(configured,manager),n,effects);return "CALLED";}
                catch(ModelBudgetExhaustedException expected){return "BUDGET";}
                catch(IllegalStateException refused){return "REFUSED";}
            }));
            assertTrue(begun.await(3,TimeUnit.SECONDS));long bound=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            while(!results.stream().allMatch(Future::isDone)) {
                String ids=pids.get(0)+","+pids.get(1);
                if(ResourceExecutionMigrationTest.count(data,"select count(distinct pid) from pg_locks where pid in ("+ids+") and not granted")==2)break;
                if(System.nanoTime()>bound)fail("writers neither refused before I/O nor reached the real registry lock wait");Thread.sleep(10);
            }
            held.commit();var outcomes=new ArrayList<String>();for(var result:results)outcomes.add(result.get(10,TimeUnit.SECONDS));
            if(isolation==Connection.TRANSACTION_READ_COMMITTED) {
                assertEquals(1,Collections.frequency(outcomes,"CALLED"));assertEquals(1,Collections.frequency(outcomes,"BUDGET"));
                assertEquals(1,effects.get());assertEquals(2000,held());
            } else {
                assertEquals(1000,held(),"unsupported writer snapshots must not mint sibling holds beyond the original2500 cap");
                assertEquals(0,effects.get());assertEquals(List.of("REFUSED","REFUSED"),outcomes);
            }
            assertEquals(1,count("model_budget_account"));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void readCommittedConcurrentSiblingsShareTheActualParentCapAfterTheLockWait() throws Exception {
        concurrentParentRace(Connection.TRANSACTION_READ_COMMITTED);
    }
    @Test void repeatableReadWritersCannotMintSiblingHoldsFromAStaleSnapshot() throws Exception {
        concurrentParentRace(Connection.TRANSACTION_REPEATABLE_READ);
    }
    @Test void serializableConcurrentWritersRefuseWithoutDowngradingTheirConfiguredIsolation() throws Exception {
        concurrentParentRace(Connection.TRANSACTION_SERIALIZABLE);
    }
    @Test void directSqlCannotBypassTheWriterIsolationGate() throws Exception {
        enter();for(int isolation:List.of(Connection.TRANSACTION_REPEATABLE_READ,Connection.TRANSACTION_SERIALIZABLE)) {
            try(var c=data.getConnection();var q=c.prepareStatement("""
                insert into task.model_budget_account(account_key,tenant,instance_id,owner_kind,owner_id,anchor_kind,anchor_id)
                values(?,?,?,?,?,'ALLOCATION',?)
                """)) {
                c.setTransactionIsolation(isolation);c.setAutoCommit(false);q.setString(1,ModelBudgetAccount.keyFor(a));q.setString(2,a.scope().tenant());
                q.setString(3,a.scope().instanceId().value());q.setString(4,a.scope().owner().kind().name());q.setString(5,a.scope().owner().id());q.setString(6,a.key());
                assertEquals("25001",assertThrows(SQLException.class,q::executeUpdate).getSQLState());c.rollback();
            }
        }
        assertEquals(0,count("model_budget_account"));assertEquals(0,held());var positive=node("graph/positive");
        answer(calls,positive,new AtomicInteger());assertEquals(1000,held());var retained=calls.find(positive).orElseThrow();
        for(int isolation:List.of(Connection.TRANSACTION_REPEATABLE_READ,Connection.TRANSACTION_SERIALIZABLE)) {
            var n=node("graph/direct-sql");try(var c=data.getConnection()) {
                c.setTransactionIsolation(isolation);c.setAutoCommit(false);
                try(var q=c.prepareStatement("""
                    insert into task.model_call_reservation(reservation_key,call_key,entry_key,executor,node_occurrence,
                        request_fingerprint,max_output_tokens,intent_fingerprint,allocation_key,allocation_fingerprint,account_key)
                    values(?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    q.setString(1,n.key());q.setString(2,n.entry().callKey());q.setString(3,n.entry().fingerprint());q.setObject(4,n.executor());
                    q.setString(5,n.nodeOccurrence());q.setString(6,n.requestFingerprint());q.setInt(7,n.maxOutputTokens());q.setString(8,n.fingerprint());
                    q.setString(9,a.key());q.setString(10,a.fingerprint());q.setString(11,retained.account().key());
                    assertEquals("25001",assertThrows(SQLException.class,q::executeUpdate).getSQLState());c.rollback();
                }
                try(var q=c.prepareStatement("select task.model_call_current_v1(?,?,?,?,?)")) {
                    q.setString(1,n.entry().callKey());q.setString(2,a.fingerprint());q.setString(3,n.entry().fingerprint());
                    q.setObject(4,n.executor());q.setString(5,retained.account().key());
                    assertEquals("25001",assertThrows(SQLException.class,q::executeQuery).getSQLState());c.rollback();
                }
            }
        }
        assertEquals(1,count("model_call_reservation"));assertEquals(1000,held());
    }
}
