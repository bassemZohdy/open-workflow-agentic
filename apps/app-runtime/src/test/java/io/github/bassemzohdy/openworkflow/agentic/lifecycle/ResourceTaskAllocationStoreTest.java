package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.invoke;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.wrapping;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceTaskAllocations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Actual full V77 plus explicit proposed V78 in owned DBs. No profile approval/model reservation/dispatch proof. */
@QuarkusTest
class ResourceTaskAllocationStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    @Inject Instance<ResourceTaskAllocations> defaults;
    ResourceTaskAllocationMigrationTest support;
    DataSource data;
    JdbcResourceTaskAllocations store;
    ResourceExecutionMigrationTest.Fixture f;
    ResourceTaskAllocation a;
    @BeforeEach void create() throws Exception {
        // Reuse the rehearsal's fixture helpers without inheriting or duplicating its @Test cases.
        support=new ResourceTaskAllocationMigrationTest();support.create();support.apply();data=support.db.owner;
        f=fixture(support.db,"a".repeat(64));a=support.candidate(f);store=new JdbcResourceTaskAllocations(data,manager);
    }
    @AfterEach void drop() throws Exception {if(support!=null)support.close();}
    long rows() throws SQLException {return count(data,"select count(*) from task.resource_task_allocation");}
    static ResourceTaskAllocation changed(ResourceTaskAllocation a) {
        var t=a.contract();var task=new AgentTaskContract(t.id(),"changed approval",t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),t.requestingSubject(),t.delegationGrantId());
        return new ResourceTaskAllocation(a.scope(),a.parentTaskId(),task,a.modelProfile());
    }
    String scalar(String sql) throws Exception {try(var c=data.getConnection()){return ResourceTaskAllocationMigrationTest.scalar(c,sql);}}
    DelegationGrants grants(Clock clock) {
        return new DelegationGrants(new DelegationGrantStore(data),new IdentityAudit(e->{},clock,UlidGenerator.shared()),clock,UlidGenerator.shared(),Duration.ofHours(2));
    }
    void corrupt(String sql) throws Exception {
        Transactions.inTransaction(data,c->{try(var s=c.createStatement()){s.execute("set local session_replication_role=replica");s.executeUpdate(sql);}return null;});
    }
    @Test void exactRepeatAndReopenRetainOneOriginalContractAndLifetimeWithoutDefaultInstallation() throws Exception {
        assertTrue(defaults.isUnsatisfied());var first=store.allocate(a);
        assertEquals(first,store.allocate(a));assertEquals(first,new JdbcResourceTaskAllocations(data,manager).find(a.scope()).orElseThrow());
        assertEquals(1,rows());assertEquals(a,first.allocation());assertEquals(0,first.createdAt().getNano()%1000);
        assertFalse(first.deadline().isAfter(first.createdAt().plus(a.contract().limits().maxWallClock())));
        assertEquals(0,count(data,"select count(*) from task.resource_execution"));
    }
    @Test void conflictsAndFullScopeMismatchAreNotAbsenceOrAnotherAllocation() throws Exception {
        store.allocate(a);assertThrows(IllegalStateException.class,()->store.allocate(changed(a)));assertEquals(1,rows());
        var s=a.scope();var wrong=new ResourceCallScope(s.tenant(),s.instanceId(),s.owner(),s.applicationId(),s.engineInstanceId(),s.namespace(),s.definition(),s.version(),s.taskPosition(),s.iteration(),s.logicalOccurrence(),s.audience(),s.operation(),"d".repeat(64),s.requestFingerprint());
        assertEquals(s.key(),wrong.key());assertThrows(IllegalStateException.class,()->store.find(wrong));
        var absent=fixture(support.db,"a".repeat(64));assertTrue(store.find(absent.binding().scope()).isEmpty());
    }
    @Test void concurrentExactRetriesHaveOneOriginalRecord() throws Exception {
        var begin=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);
        try {
            var results=new ArrayList<Future<AllocatedResourceTask>>();for(int i=0;i<6;i++)results.add(pool.submit(()->{begin.await();return store.allocate(a);}));
            begin.countDown();var first=results.getFirst().get(15,TimeUnit.SECONDS);for(var r:results)assertEquals(first,r.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,rows());
    }
    @Test void originalLifetimeRemainsHistoricalAfterRegistryCompletionAndGrantRevocation() throws Exception {
        var first=store.allocate(a);var registry=new JdbcInstanceStore(data);var row=f.row();
        registry.replace(row.successor(databaseNow(data),InstanceStatus.COMPLETED,row.engineInstanceId(),row.startAttempts(),null,null),row.version(),"test.complete",row.owner());
        grants(Clock.systemUTC()).revoke(a.contract().delegationGrantId(),a.scope().owner(),"test revoke",null);
        assertEquals(first,store.allocate(a));assertEquals(first,store.find(a.scope()).orElseThrow());assertEquals(1,rows());
        // Exact expired repeats are DATA and do not reauthorize, refresh deadline, reset budget or enter a graph.
    }
    @Test void anActuallyExpiredOriginalDeadlineIsNeverRenewedByExactRetry() throws Exception {
        var now=databaseNow(data);var root=new DelegationGrantStore(data).find(f.binding().issuingGrantId()).orElseThrow();
        var child=grants(Clock.fixed(now,ZoneOffset.UTC)).delegate(root.id(),root.agent(),a.modelProfile().agent(),Set.of("oa.agent.run"),now.plusSeconds(5),null);
        var t=a.contract();var contract=new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),t.requestingSubject(),child.id());
        var candidate=new ResourceTaskAllocation(a.scope(),null,contract,a.modelProfile());var first=store.allocate(candidate);
        long bound=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        try(var c=data.getConnection();var q=c.createStatement()) {
            while(true) {
                try(var r=q.executeQuery("select clock_timestamp()")){r.next();if(!r.getTimestamp(1).toInstant().isBefore(first.deadline()))break;}
                if(System.nanoTime()>bound)fail("database clock did not cross the original bounded deadline");Thread.sleep(20);
            }
        }
        assertEquals(first,store.allocate(candidate));assertEquals(first,store.find(candidate.scope()).orElseThrow());assertEquals(1,rows());
    }
    @Test void actualCommitThenLostReplyReopensTheSameStoredTaskWithoutRenewal() throws Exception {
        var fail=new AtomicBoolean(true);var lost=wrapping(data,c->(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
            Object result=invoke(m,c,args);if(m.getName().equals("commit")&&fail.getAndSet(false))throw new SQLException("actual allocation reply lost","08006");return result;
        }));
        assertThrows(StoreUnavailableException.class,()->new JdbcResourceTaskAllocations(lost,manager).allocate(a));
        var recovered=store.find(a.scope()).orElseThrow();assertEquals(recovered,store.allocate(a));assertEquals(1,rows());
    }
    @Test void actualCallerRollbackCannotEraseTheCommittedAllocation() throws Exception {
        caller.begin();AllocatedResourceTask first;
        try{first=store.allocate(a);assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertEquals(first,store.find(a.scope()).orElseThrow());assertEquals(1,rows());
    }
    @Test void unavailableStoreIsNotAnAbsentAllocation() {
        var unavailable=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(p,m,args)->{throw new SQLException("offline","08006");});
        var failed=new JdbcResourceTaskAllocations(unavailable,manager);
        assertThrows(StoreUnavailableException.class,()->failed.find(a.scope()));assertThrows(StoreUnavailableException.class,()->failed.allocate(a));
    }
    @Test void corruptStoredContractAndMissingOccurrenceRefuseRatherThanDropMetadata() throws Exception {
        store.allocate(a);corrupt("update task.resource_task_allocation set contract=contract||'{\"extra\":true}'::jsonb");
        assertThrows(IllegalStateException.class,()->store.find(a.scope()));
        corrupt("update task.resource_task_allocation set contract=contract-'extra'");
        assertEquals(a,store.find(a.scope()).orElseThrow().allocation());
        corrupt("delete from task.resource_occurrence");assertThrows(IllegalStateException.class,()->store.find(a.scope()));
    }
    @Test void databaseDataGuardStillRequiresCurrentRegistryForANewAllocation() throws Exception {
        var registry=new JdbcInstanceStore(data);var row=f.row();
        registry.replace(row.successor(databaseNow(data),InstanceStatus.CANCEL_REQUESTED,row.engineInstanceId(),row.startAttempts(),null,null),row.version(),"test.cancel",row.owner());
        assertThrows(StoreUnavailableException.class,()->store.allocate(a));assertEquals(0,rows());
    }
    @Test void aGrantRevokedWhileTheActualRegistryLockBlocksIsNotUsedByTheNewAllocation() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        try(var held=data.getConnection()) {
            held.setAutoCommit(false);try(var q=held.prepareStatement("select id from task.instance where id=? for update")){q.setString(1,a.scope().instanceId().value());q.executeQuery().close();}
            var pid=new AtomicInteger();var started=new CountDownLatch(1);
            var observed=wrapping(data,c->{pid.set(Integer.parseInt(ResourceTaskAllocationMigrationTest.scalar(c,"select pg_backend_pid()")));started.countDown();return c;});
            var pending=pool.submit(()->new JdbcResourceTaskAllocations(observed,manager).allocate(a));
            assertTrue(started.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(count(data,"select count(*) from pg_locks where pid="+pid.get()+" and not granted")==0) {if(System.nanoTime()>end)fail("allocator did not block on registry lock");Thread.sleep(10);}
            grants(Clock.systemUTC()).revoke(a.contract().delegationGrantId(),a.scope().owner(),"blocked allocation revoke",null);held.commit();
            var error=assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));assertInstanceOf(StoreUnavailableException.class,error.getCause());assertEquals(0,rows());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void actualParentTaskIsRetainedWithoutInventingTheCanonicalCallThread() throws Exception {
        var t=a.contract();var ids=UlidGenerator.shared();
        var parentContract=new AgentTaskContract(ids.next(),"parent",t.permittedTools(),t.permittedDataScopes(),RiskTier.MEDIUM,
                new ExecutionLimits(10,Duration.ofSeconds(600),10000),t.requestingSubject(),f.binding().issuingGrantId());
        var parent=new AgentTaskRecord(parentContract.id(),a.scope().tenant(),a.scope().owner(),a.scope().instanceId().value(),
                ThreadId.derive(a.scope().tenant(),a.scope().owner().id(),a.scope().instanceId().value()),parentContract,AgentTaskStatus.PENDING,0);
        var tasks=new JdbcTaskStore(data);tasks.insert(parent);
        var child=new ResourceTaskAllocation(a.scope(),parent.taskId(),t,a.modelProfile());var first=store.allocate(child);
        assertEquals(parent,tasks.find(parent.taskId()).orElseThrow());assertNotEquals(parent.threadId(),first.allocation().scope().threadId());
        assertEquals(first,store.find(a.scope()).orElseThrow());
    }
    @Test void inheritedSerializableAndTimeoutSettingsAreNotDowngraded() throws Exception {
        var seen=new ArrayList<String>();var configured=wrapping(data,c->{
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var s=c.createStatement()){s.execute("set lock_timeout='200ms';set statement_timeout='500ms'");}
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
                if(m.getName().equals("commit"))seen.add(ResourceTaskAllocationMigrationTest.scalar(c,"select current_setting('transaction_isolation')||':'||current_setting('lock_timeout')||':'||current_setting('statement_timeout')"));
                return invoke(m,c,args);
            });
        });
        var bounded=new JdbcResourceTaskAllocations(configured,manager);var first=bounded.allocate(a);assertEquals(first,bounded.find(a.scope()).orElseThrow());
        assertFalse(seen.isEmpty());assertTrue(seen.stream().allMatch(x->x.equals("serializable:200ms:500ms")));
    }
    @Test void resumeFailurePreservesPrimaryOrRefusesReturnAfterCommittedData() throws Exception {
        var faulty=(TransactionManager)Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),new Class[]{TransactionManager.class},(p,m,args)->{
            Object result=invoke(m,manager,args);if(m.getName().equals("resume"))throw new SystemException("resume diagnostic");return result;
        });
        var failed=new JdbcResourceTaskAllocations(data,faulty);store.allocate(a);
        caller.begin();try {
            var error=assertThrows(IllegalStateException.class,()->failed.allocate(changed(a)));assertEquals(1,error.getSuppressed().length);
            assertInstanceOf(SystemException.class,error.getSuppressed()[0]);assertEquals(Status.STATUS_ACTIVE,caller.getStatus());
        } finally {caller.rollback();}
        var other=fixture(support.db,"a".repeat(64));var candidate=support.candidate(other);
        caller.begin();try{assertThrows(StoreUnavailableException.class,()->failed.allocate(candidate));assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertTrue(store.find(candidate.scope()).isPresent());assertEquals(2,rows());
    }
}
