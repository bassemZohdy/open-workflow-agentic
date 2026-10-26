package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Real PG entry/acknowledgement tests; fixture fences are NOT actual identity, policy or Flow integration. */
class ResourceExecutionGateTest {
    private static final ResourceExecutionFence FIXTURE_ONLY = entry -> { };
    private static JdbcResourceExecutionGate gate(Database db) { return new JdbcResourceExecutionGate(db.owner,8); }
    private static JdbcResourceExecutionGate.Attempt open(JdbcResourceExecutionGate gate,Fixture f) {
        return gate.open(f.binding(),f.entry(1),UUID.randomUUID(),FIXTURE_ONLY);
    }
    @Test void freshAcknowledgedEntryCallsOnceAndRetainedDataNeverCallsAgain() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var calls=new AtomicInteger();
            try(var gate=gate(db)) {
                assertTrue(gate.find(f.binding().scope()).isEmpty());
                var attempt=open(gate,f);var result=attempt.execute(entry->{calls.incrementAndGet();return "fixture-result";});
                assertEquals(Kind.CALLED,result.kind());assertEquals("fixture-result",result.value());
                assertFalse(result.toString().contains("fixture-result"));
                assertEquals(f.entry(1),result.fact().entry());assertEquals(f.binding().key(),result.fact().firstBindingKey());
                assertThrows(RefusedException.class,()->attempt.execute(entry->"must-not-run"));
                var again=open(gate,f).execute(entry->{calls.incrementAndGet();return "duplicate";});
                assertEquals(Kind.RETAINED,again.kind());assertNull(again.value());assertEquals(result.fact(),again.fact());
                assertEquals(1,calls.get());assertEquals(0,gate.activeCount());
            }
        }
    }
    @Test void eightConcurrentCredentialsAndExecutorsHaveOnlyOneCallback() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            var now=databaseNow(db.owner);var second=credential(db,f,now.minusSeconds(1),now.plusSeconds(120));
            var pool=Executors.newFixedThreadPool(8);var calls=new AtomicInteger();var start=new CountDownLatch(1);
            try(var gate=gate(db)) {
                var futures=new ArrayList<Future<Result<Integer>>>();
                for(int i=0;i<8;i++) {
                    var binding=i%2==0?f.binding():second;
                    var attempt=gate.open(binding,f.entry(1),UUID.randomUUID(),FIXTURE_ONLY);
                    futures.add(pool.submit(()->{start.await();return attempt.execute(entry->calls.incrementAndGet());}));
                }
                start.countDown();var results=new ArrayList<Result<Integer>>();
                for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));
                assertEquals(1,results.stream().filter(r->r.kind()==Kind.CALLED).count());assertEquals(1,calls.get());
                assertEquals(1,results.stream().map(Result::fact).distinct().count());assertEquals(0,gate.activeCount());
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }
    @Test void cancellationAndNewEpochCannotEraseOrReplaceTheOriginalEntry() throws Exception {
        try(var db=new Database();var gate=gate(db)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var first=open(gate,f).execute(entry->"done");
            execute(db.owner,"update task.instance set status='CANCEL_REQUESTED' where id='"+f.row().id()+"'");
            assertEquals(first.fact(),gate.find(f.binding().scope()).orElseThrow());
            assertEquals(Kind.RETAINED,open(gate,f).execute(entry->{fail("historical data cannot dispatch");return null;}).kind());
            var another=gate.open(f.binding(),new ResourceExecutionEntry(f.binding().scope(),2),UUID.randomUUID(),FIXTURE_ONLY);
            assertThrows(RefusedException.class,()->another.execute(entry->{fail("new epoch cannot dispatch");return null;}));
            assertEquals(first.fact(),gate.find(f.binding().scope()).orElseThrow());
        }
    }
    private enum Fault { ACQUIRE, INSERT, BEFORE_COMMIT, AFTER_COMMIT, CLOSE, SUSPEND, RESUME }
    @TestFactory Stream<DynamicTest> storageAndAmbientReplyFailuresNeverCallTheCallback() {
        return Arrays.stream(Fault.values()).map(fault->DynamicTest.dynamicTest(fault.name(),()->{
            try(var db=new Database()) {
                db.migrate(null);var f=fixture(db,"a".repeat(64));var calls=new AtomicInteger();
                Transaction transaction=proxy(Transaction.class,(method,args)->null);
                TransactionManager tm=proxy(TransactionManager.class,(method,args)->{
                    if(method.getName().equals("suspend")) {
                        if(fault==Fault.SUSPEND)throw new SystemException("fixture suspend failure");
                        return transaction;
                    }
                    if(method.getName().equals("resume")&&fault==Fault.RESUME)throw new SystemException("fixture resume failure");
                    return null;
                });
                DataSource source=proxy(DataSource.class,(method,args)->{
                    if(method.getName().equals("getConnection")) {
                        if(fault==Fault.ACQUIRE)throw new SQLException("fixture acquisition failure");
                        Connection real=(Connection)invoke(db.owner,method,args);
                        return proxy(Connection.class,(cm,ca)->{
                            if(cm.getName().equals("commit")&&fault==Fault.BEFORE_COMMIT)throw new SQLException("fixture pre-commit reply loss");
                            Object value=invoke(real,cm,ca);
                            if(cm.getName().equals("commit")&&fault==Fault.AFTER_COMMIT)throw new SQLException("fixture actual commit reply loss");
                            if(cm.getName().equals("close")&&fault==Fault.CLOSE)throw new SQLException("fixture actual close reply loss");
                            if(cm.getName().equals("prepareStatement")&&ca[0].toString().contains("insert into task.resource_execution")) {
                                return proxy(PreparedStatement.class,(sm,sa)->{
                                    if(sm.getName().equals("executeUpdate")&&fault==Fault.INSERT)throw new SQLException("fixture insert failure");
                                    return invoke(value,sm,sa);
                                });
                            }
                            return value;
                        });
                    }
                    return invoke(db.owner,method,args);
                });
                try(var broken=new JdbcResourceExecutionGate(source,tm,1);var normal=gate(db)) {
                    boolean uncertain=Set.of(Fault.BEFORE_COMMIT,Fault.AFTER_COMMIT,Fault.CLOSE,Fault.RESUME).contains(fault);
                    Class<? extends RuntimeException> expectedFailure=uncertain?CommitUncertainException.class:StoreUnavailableException.class;
                    assertThrows(expectedFailure,
                            ()->open(broken,f).execute(entry->{calls.incrementAndGet();return "unsafe";}));
                    boolean applied=Set.of(Fault.AFTER_COMMIT,Fault.CLOSE,Fault.RESUME).contains(fault);
                    assertEquals(applied,normal.find(f.binding().scope()).isPresent());assertEquals(0,calls.get());
                    if(applied)assertEquals(Kind.RETAINED,open(normal,f).execute(entry->{calls.incrementAndGet();return "unsafe";}).kind());
                    assertEquals(0,calls.get());assertEquals(0,broken.activeCount());
                }
            }
        }));
    }
    @Test void closedUnstartedHandlesReleaseCapacityAndBeanCloseRefusesNewOnes() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var a=open(gate,f);
            assertThrows(RefusedException.class,()->open(gate,f));a.close();a.close();assertEquals(0,gate.activeCount());
            assertThrows(RefusedException.class,()->a.execute(entry->"unsafe"));
            var b=open(gate,f);gate.close();assertEquals(0,gate.activeCount());
            assertThrows(RefusedException.class,()->b.execute(entry->"unsafe"));assertThrows(RefusedException.class,()->open(gate,f));
            assertTrue(gate.find(f.binding().scope()).isEmpty());
        }
    }
    @Test void closeDuringARealRegistryLockWaitKeepsItsSlotUntilActualTermination() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var source=db.data();String application="entry-wait-"+UUID.randomUUID();
            source.setApplicationName(application);
            try(var workerGate=new JdbcResourceExecutionGate(source,1);var lock=db.owner.getConnection()) {
                lock.setAutoCommit(false);try(var q=lock.prepareStatement("select id from task.instance where id=? for update")) {
                    q.setString(1,f.row().id().value());try(var ignored=q.executeQuery()){assertTrue(ignored.next());}
                }
                var attempt=open(workerGate,f);var pool=Executors.newSingleThreadExecutor();var calls=new AtomicInteger();
                try {
                    var work=pool.submit(()->attempt.execute(entry->calls.incrementAndGet()));
                    await(()->count(db.owner,"select count(*) from pg_stat_activity where application_name='"+application+"' and wait_event_type='Lock'")==1);
                    attempt.close();assertEquals(1,workerGate.activeCount());assertFalse(work.isDone());
                    assertThrows(RefusedException.class,()->open(workerGate,f));
                    lock.rollback();var refused=assertThrows(ExecutionException.class,()->work.get(10,TimeUnit.SECONDS));
                    assertInstanceOf(RefusedException.class,refused.getCause());assertEquals(0,calls.get());
                    assertEquals(0,workerGate.activeCount());assertTrue(gate.find(f.binding().scope()).isPresent());
                    assertEquals(Kind.RETAINED,open(workerGate,f).execute(entry->{fail("closed committed entry must not re-run");return null;}).kind());
                } finally {lock.rollback();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
            }
        }
    }
    @Test void revocationAfterCommitAndAfterCallbackWithholdsWorkOrPrivateOutput() throws Exception {
        for(boolean afterCallback:List.of(false,true))try(var db=new Database();var gate=gate(db)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var calls=new AtomicInteger();
            ResourceExecutionFence fixture=entry->{
                if(afterCallback?calls.get()>0:gate.find(entry.scope()).isPresent())throw new RefusedExceptionForFixture();
            };
            var attempt=gate.open(f.binding(),f.entry(1),UUID.randomUUID(),fixture);
            assertThrows(RefusedExceptionForFixture.class,()->attempt.execute(entry->{calls.incrementAndGet();return "private-fixture-result";}));
            assertEquals(afterCallback?1:0,calls.get());assertTrue(gate.find(f.binding().scope()).isPresent());
            assertEquals(Kind.RETAINED,open(gate,f).execute(entry->{fail("withheld output is not non-entry");return null;}).kind());
        }
    }
    @Test void callbackFailureRetainsEntryAndPreexistingInterruptIsPreserved() throws Exception {
        try(var db=new Database();var gate=gate(db)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            assertThrows(RefusedExceptionForFixture.class,()->open(gate,f).execute(entry->{throw new RefusedExceptionForFixture();}));
            assertTrue(gate.find(f.binding().scope()).isPresent());
            var attempt=open(gate,f);Thread.currentThread().interrupt();
            try {assertThrows(RefusedException.class,()->attempt.execute(entry->"unsafe"));assertTrue(Thread.currentThread().isInterrupted());}
            finally {Thread.interrupted();}
            assertEquals(0,gate.activeCount());
        }
    }
    @Test void forgedBindingMetadataAndSameKeyDifferentFullScopeNeverReachTheCallback() throws Exception {
        try(var db=new Database();var gate=gate(db)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var b=f.binding();var s=b.scope();
            var changed=new ResourceCallScope(s.tenant(),s.instanceId(),s.owner(),s.applicationId(),s.engineInstanceId(),
                    s.namespace(),s.definition(),s.version(),s.taskPosition(),s.iteration(),s.logicalOccurrence(),
                    s.audience(),s.operation(),s.targetFingerprint(),"f".repeat(64));
            assertEquals(s.key(),changed.key());assertNotEquals(s,changed);
            var candidates=List.of(
                    new ResourceCallBinding(b.issuer(),UUID.randomUUID().toString(),b.issuingGrantId(),s,b.issuedAt(),b.expiresAt()),
                    new ResourceCallBinding(b.issuer(),b.tokenId(),b.issuingGrantId(),s,b.issuedAt(),b.expiresAt().minusSeconds(1)),
                    new ResourceCallBinding(b.issuer(),b.tokenId(),b.issuingGrantId(),changed,b.issuedAt(),b.expiresAt()));
            for(var candidate:candidates) {
                var attempt=gate.open(candidate,new ResourceExecutionEntry(candidate.scope(),1),UUID.randomUUID(),FIXTURE_ONLY);
                assertThrows(RefusedException.class,()->attempt.execute(entry->{fail("forged metadata cannot call");return null;}));
            }
            assertTrue(gate.find(s).isEmpty());
            open(gate,f).execute(entry->"fixture");
            assertThrows(RefusedException.class,()->gate.find(changed));
        }
    }
    @Test void interruptedClosedWorkerKeepsItsSlotWhileAnActualCommittedReplyIsStillBlocked() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            var committed=new CountDownLatch(1);var release=new CountDownLatch(1);var worker=new AtomicReference<Thread>();
            DataSource source=proxy(DataSource.class,(method,args)->{
                Object value=invoke(db.owner,method,args);
                if(method.getName().equals("getConnection"))return proxy(Connection.class,(cm,ca)->{
                    Object answer=invoke(value,cm,ca);
                    if(cm.getName().equals("commit")) {
                        committed.countDown();boolean interrupted=false;
                        for(;;)try {release.await();break;}catch(InterruptedException ignored){interrupted=true;}
                        if(interrupted)Thread.currentThread().interrupt();
                    }
                    return answer;
                });
                return value;
            });
            var pool=Executors.newSingleThreadExecutor();var observedInterrupt=new AtomicBoolean();var calls=new AtomicInteger();
            try(var gate=new JdbcResourceExecutionGate(source,1);var normal=gate(db)) {
                var attempt=open(gate,f);
                var work=pool.submit(()->{
                    worker.set(Thread.currentThread());
                    try{return attempt.execute(entry->calls.incrementAndGet());}
                    finally {observedInterrupt.set(Thread.currentThread().isInterrupted());}
                });
                try {
                    assertTrue(committed.await(10,TimeUnit.SECONDS));assertTrue(normal.find(f.binding().scope()).isPresent());
                    attempt.close();worker.get().interrupt();assertEquals(1,gate.activeCount());
                    assertThrows(RefusedException.class,()->open(gate,f));
                    assertThrows(TimeoutException.class,()->work.get(100,TimeUnit.MILLISECONDS));
                    release.countDown();var failure=assertThrows(ExecutionException.class,()->work.get(10,TimeUnit.SECONDS));
                    assertInstanceOf(RefusedException.class,failure.getCause());assertTrue(observedInterrupt.get());
                    assertEquals(0,calls.get());assertEquals(0,gate.activeCount());
                    assertEquals(Kind.RETAINED,open(normal,f).execute(entry->{fail("unknown execution never restarts");return null;}).kind());
                } finally {release.countDown();}
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }
    @Test void anAlreadyTransactionalConnectionIsRefusedWithoutCommittingOrRollingBackItsOwner() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            try(var foreign=db.owner.getConnection()) {
                foreign.setAutoCommit(false);
                try(var q=foreign.prepareStatement("update task.instance set status='WAITING' where id=?")) {
                    q.setString(1,f.row().id().value());assertEquals(1,q.executeUpdate());
                }
                Connection borrowed=proxy(Connection.class,(method,args)->{
                    if(Set.of("commit","rollback").contains(method.getName()))fail("gate must not change a foreign transaction");
                    if(method.getName().equals("close"))return null; // fixture keeps the unsupported foreign lease inspectable
                    return invoke(foreign,method,args);
                });
                DataSource source=proxy(DataSource.class,(method,args)->method.getName().equals("getConnection")?borrowed:invoke(db.owner,method,args));
                try(var gate=new JdbcResourceExecutionGate(source,1)) {
                    assertThrows(RefusedException.class,()->open(gate,f).execute(entry->{fail("foreign transaction cannot dispatch");return null;}));
                    assertFalse(foreign.getAutoCommit());
                    try(var q=foreign.createStatement();var r=q.executeQuery("select count(*) from task.instance where status='WAITING'")) {
                        assertTrue(r.next());assertEquals(1,r.getLong(1));
                    }
                    assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
                } finally {foreign.rollback();}
            }
        }
    }
    private static class RefusedExceptionForFixture extends RuntimeException { }
    @FunctionalInterface private interface Call {Object invoke(Method method,Object[] args) throws Throwable;}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m,a));
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    @FunctionalInterface private interface Condition {boolean get() throws Exception;}
    private static void await(Condition condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end){if(condition.get())return;Thread.sleep(20);}fail("owned database wait not observed");
    }
}
