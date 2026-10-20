package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.TaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Actual JDBC/proposed V78 on owned committed UUID schemas. No policy/JWT/model/graph/budget dispatch proof. */
@QuarkusTest
class ResourceTaskAllocationStoreTest extends ResourceAllocationFixture {
    @Test void exactRepeatReopenAndCredentialRefreshRetainOneOriginalAllocationAndLegacyParent() throws Exception {
        var f=fixture(true);
        try(var a=new Arena(f)) {
            var first=a.store.allocate(f.allocation());assertEquals(first,a.store.allocate(f.allocation()));
            assertEquals(first,new JdbcResourceTaskAllocations(a.redirected,transactions).find(f.binding().scope()).orElseThrow());
            assertEquals(f.parent(),tasks.find(f.parent().taskId()).orElseThrow());
            assertNotEquals(f.parent().threadId(),first.allocation().scope().threadId());
            String jti=UUID.randomUUID().toString();var b=f.binding();
            new JdbcIssuedTokenBindings(data).bind(b.issuer(),jti,new IssuedTokenBindings.Binding(b.issuingGrantId(),b.scope().tenant(),b.expiresAt()));
            var refreshed=new ResourceCallBinding(b.issuer(),jti,b.issuingGrantId(),b.scope(),b.issuedAt(),b.expiresAt());bindings.bind(refreshed);
            try(var c=data.getConnection();var q=c.prepareStatement("insert into "+a.schema+".resource_call_binding select * from task.resource_call_binding where binding_key=?")) {
                q.setString(1,refreshed.key());q.executeUpdate();
            }
            assertEquals(first,a.store.allocate(f.allocation()));assertEquals(1,a.count());
            assertEquals(0,first.createdAt().getNano()%1000);assertEquals(0,first.deadline().getNano()%1000);
            assertFalse(first.deadline().isAfter(first.createdAt().plusSeconds(120)));
        }
    }
    @Test void sdkRetryCounterMustResolveToTheFirstCanonicalCallBeforeAllocation() throws Exception {
        var f=fixture(false);var s=f.binding().scope();
        var retry=new ResourceCallScope(s.tenant(),s.instanceId(),s.owner(),s.applicationId(),s.engineInstanceId(),s.namespace(),
                s.definition(),s.version(),s.taskPosition(),2,s.logicalOccurrence(),s.audience(),s.operation(),s.targetFingerprint(),s.requestFingerprint());
        try(var a=new Arena(f)) {
            var first=a.store.allocate(f.allocation());
            refused("23503",()->a.store.allocate(new ResourceTaskAllocation(retry,null,
                    f.allocation().contract(),f.allocation().modelProfile())));
            var canonical=bindings.findInvocation(retry).orElseThrow();assertEquals(s,canonical);
            assertEquals(first,a.store.allocate(new ResourceTaskAllocation(canonical,null,f.allocation().contract(),f.allocation().modelProfile())));
            assertEquals(1,a.count());
        }
    }
    @Test void concurrentExactAllocationsShareOneContractAndOriginalDeadline() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f)) {
            var ready=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);
            try {
                List<Future<AllocatedResourceTask>> results=new ArrayList<>();
                for(int i=0;i<6;i++)results.add(pool.submit(()->{ready.await();return a.store.allocate(f.allocation());}));
                ready.countDown();var first=results.get(0).get(20,TimeUnit.SECONDS);
                for(var result:results)assertEquals(first,result.get(20,TimeUnit.SECONDS));
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            assertEquals(1,a.count());
        }
    }
    @Test void concurrentChangedApprovalsHaveOneWinnerAndNeverTwoAllocations() throws Exception {
        var f=fixture(false);var t=f.allocation().contract();var changed=withTask(f.allocation(),
                task(t,"different approved goal",t.permittedTools(),t.limits(),t.delegationGrantId()));
        try(var a=new Arena(f)) {
            var ready=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try {
                List<Future<Boolean>> results=new ArrayList<>();
                for(var candidate:List.of(f.allocation(),changed))results.add(pool.submit(()->{
                    ready.await();try{a.store.allocate(candidate);return true;}catch(IllegalStateException conflict){return false;}
                }));
                ready.countDown();assertNotEquals(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS));
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            assertEquals(1,a.count());
        }
    }
    @Test void unknownActualCommitReplyRetainsExactlyTheSameAllocationOnReopen() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f)) {
            a.loseCommit.set(true);refused("08006",()->a.store.allocate(f.allocation()));
            var retained=new JdbcResourceTaskAllocations(a.redirected,transactions).find(f.binding().scope()).orElseThrow();
            assertEquals(retained,a.store.allocate(f.allocation()));assertEquals(1,a.count());
        }
    }
    @Test void callerJtaRollbackDoesNotEraseTheCommittedAllocation() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f)) {
            callerTransaction.begin();
            try{a.store.allocate(f.allocation());assertEquals(jakarta.transaction.Status.STATUS_ACTIVE,callerTransaction.getStatus());}
            finally{callerTransaction.rollback();}
            assertTrue(a.store.find(f.binding().scope()).isPresent());assertEquals(1,a.count());
        }
    }
    @Test void changedTaskProfileParentOrRequestCannotReplaceOriginalApproval() throws Exception {
        var f=fixture(true);var t=f.allocation().contract();
        try(var a=new Arena(f)) {
            var first=a.store.allocate(f.allocation());
            for(var candidate:List.of(withTask(f.allocation(),task(t,"other",t.permittedTools(),t.limits(),t.delegationGrantId())),
                    withTask(f.allocation(),task(t,t.goal(),t.permittedTools(),new ExecutionLimits(4,Duration.ofSeconds(119),4999),t.delegationGrantId())),
                    new ResourceTaskAllocation(f.allocation().scope(),null,t,f.allocation().modelProfile()),
                    new ResourceTaskAllocation(f.allocation().scope(),f.allocation().parentTaskId(),t,
                        new ApprovedModelProfile("research-𝄞","2","d".repeat(64),AGENT,"other-model",3,1000,Duration.ofSeconds(10),2048))))
                assertThrows(IllegalStateException.class,()->a.store.allocate(candidate));
            var s=f.allocation().scope();var changed=new ResourceCallScope(s.tenant(),s.instanceId(),s.owner(),s.applicationId(),s.engineInstanceId(),
                    s.namespace(),s.definition(),s.version(),s.taskPosition(),s.iteration(),s.logicalOccurrence(),s.audience(),s.operation(),
                    s.targetFingerprint(),"d".repeat(64));
            assertThrows(IllegalStateException.class,()->a.store.find(changed));
            assertEquals(first,a.store.find(s).orElseThrow());assertEquals(1,a.count());
        }
    }
    @Test void actualParentNarrowingAndRemainingLifetimeAreEnforced() throws Exception {
        var f=fixture(true);var t=f.allocation().contract();
        try(var a=new Arena(f)) {
            var widened=withTask(f.allocation(),task(t,t.goal(),Set.of("tool:unapproved"),t.limits(),t.delegationGrantId()));
            refused("23514",()->a.store.allocate(widened));assertEquals(0,a.count());
            a.change("update "+a.schema+".agent_task set created_at=clock_timestamp()+interval '60 seconds'");
            refused("23514",()->a.store.allocate(f.allocation()));assertEquals(0,a.count());
            a.change("update "+a.schema+".agent_task set created_at=clock_timestamp()-interval '590 seconds'");
            var first=a.store.allocate(f.allocation());
            try(var c=data.getConnection();var q=c.createStatement();var r=q.executeQuery("select created_at+interval '600 seconds' from "+a.schema+".agent_task")) {
                assertTrue(r.next());assertEquals(r.getTimestamp(1).toInstant(),first.deadline());
            }
        }
    }
    @Test void unrelatedOrRevokedGrantAndWrongModelAgentCannotAllocate() throws Exception {
        var f=fixture(false);var t=f.allocation().contract();
        try(var a=new Arena(f)) {
            var unrelated=grants.grant(f.row().tenant(),ALICE,ALICE,AGENT,Set.of("oa.model.call"),now().plusSeconds(600),null);
            refused("23514",()->a.store.allocate(withTask(f.allocation(),
                    task(t,t.goal(),t.permittedTools(),t.limits(),unrelated.id()))));
            var other=new ApprovedModelProfile("other","1","c".repeat(64),new Subject(SubjectKind.AGENT,"someone-else"),"model",3,1000,Duration.ofSeconds(10),2048);
            refused("23514",()->a.store.allocate(new ResourceTaskAllocation(f.binding().scope(),null,t,other)));
            grants.revoke(f.root().id(),ALICE,"test",null);
            refused("23514",()->a.store.allocate(f.allocation()));assertEquals(0,a.count());
        }
    }
    @Test void historicalReadAndExactRepeatAfterRevocationAndCompletionNeverRenewData() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f)) {
            var first=a.store.allocate(f.allocation());grants.revoke(f.root().id(),ALICE,"test",null);
            a.change("update "+a.schema+".instance set status='COMPLETED',wait_deadline=clock_timestamp()-interval '1 second'");
            assertEquals(first,a.store.allocate(f.allocation()));assertEquals(first,a.store.find(f.binding().scope()).orElseThrow());
            assertEquals(1,a.count());
        }
    }
    @Test void databaseClockIsCheckedAfterTheRegistryLockWait() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f);var lock=data.getConnection()) {
            lock.setAutoCommit(false);
            sql(lock,"update "+a.schema+".instance set wait_deadline=clock_timestamp()+interval '1 second'");
            int blocker;
            try(var q=lock.createStatement();var r=q.executeQuery("select pg_backend_pid()")){assertTrue(r.next());blocker=r.getInt(1);}
            var begun=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
            try {
                var result=pool.submit(()->{begun.countDown();return refused("23514",()->a.store.allocate(f.allocation()));});
                assertTrue(begun.await(5,TimeUnit.SECONDS));
                boolean blocked=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                try(var monitor=data.getConnection();var q=monitor.prepareStatement(
                        "select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))")) {
                    q.setInt(1,blocker);
                    while(System.nanoTime()<until) {
                        try(var r=q.executeQuery()){assertTrue(r.next());if(r.getBoolean(1)){blocked=true;break;}}
                        Thread.sleep(10);
                    }
                }
                assertTrue(blocked,"the actual allocation session must be observed waiting on this owned registry lock");
                Thread.sleep(1200);lock.commit();result.get(15,TimeUnit.SECONDS);
            } finally {lock.rollback();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
            assertEquals(0,a.count());
        }
    }
    @Test void absentOrCorruptMetadataAndUnavailableStorageRefuseInsteadOfAuthorizing() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f)) {
            var unavailable=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},
                    (p,m,args)->{throw new SQLException("test outage","08006");});
            refused("08006",()->new JdbcResourceTaskAllocations(unavailable).find(f.binding().scope()));
            a.change("alter table "+a.schema+".resource_task_allocation rename to temporarily_unavailable_allocation");
            try {refused("42P01",()->a.store.find(f.binding().scope()));}
            finally {a.change("alter table "+a.schema+".temporarily_unavailable_allocation rename to resource_task_allocation");}
            a.store.allocate(f.allocation());
            a.change("alter table "+a.schema+".resource_occurrence disable trigger user");
            a.change("delete from "+a.schema+".resource_occurrence");
            assertThrows(IllegalStateException.class,()->a.store.find(f.binding().scope()));
        }
    }
    @Test void exactRetainedJsonRejectsUnknownFieldsRatherThanDiscardingThem() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f)) {
            a.store.allocate(f.allocation());
            a.change("alter table "+a.schema+".resource_task_allocation disable trigger user");
            a.change("update "+a.schema+".resource_task_allocation set model_profile=model_profile||'{\"unapproved\":true}'::jsonb");
            assertThrows(IllegalStateException.class,()->a.store.find(f.binding().scope()));
        }
    }
    @Test void executionRequiresAllocationAndRetainedFactsCannotBeChangedOrDeleted() throws Exception {
        var f=fixture(true);
        try(var a=new Arena(f);var c=data.getConnection()) {
            c.setAutoCommit(false);
            var point=c.setSavepoint();
            var missing=assertThrows(SQLException.class,()->ResourceExecutionSchemaTest.enter(c,a.schema,f.binding(),1,Map.of()));
            assertEquals("23503",missing.getSQLState());c.rollback(point);c.commit();
            var first=a.store.allocate(f.allocation());
            ResourceExecutionSchemaTest.enter(c,a.schema,f.binding(),1,Map.of());c.commit();
            for(String statement:List.of("update "+a.schema+".resource_task_allocation set deadline=deadline+interval '1 second'",
                    "delete from "+a.schema+".resource_task_allocation","truncate "+a.schema+".resource_task_allocation cascade")) {
                var saved=c.setSavepoint();try {var refused=assertThrows(SQLException.class,()->sql(c,statement));assertEquals("42501",refused.getSQLState());}
                finally{c.rollback(saved);}
            }
            var saved=c.setSavepoint();try{var retained=assertThrows(SQLException.class,()->sql(c,"delete from "+a.schema+".agent_task"));assertEquals("23503",retained.getSQLState());}
            finally{c.rollback(saved);}
            assertEquals(first,a.store.allocate(f.allocation()));assertEquals(1,a.count());
        }
    }
    @Test void schemaActivationStopsAtUnallocatedHistoricalExecutionWithoutDeletingIt() throws Exception {
        var f=fixture(false);
        try(var a=new Arena(f,false);var c=data.getConnection()) {
            ResourceExecutionSchemaTest.enter(c,a.schema,f.binding(),1,Map.of());
            var refused=assertThrows(SQLException.class,()->a.apply(c,"proposed/V78__resource_task_allocation.sql"));
            assertEquals("23503",refused.getSQLState());
            try(var q=c.createStatement();var r=q.executeQuery("select count(*) from "+a.schema+".resource_execution")) {assertTrue(r.next());assertEquals(1,r.getLong(1));}
        }
    }
}
