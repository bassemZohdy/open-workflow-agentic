package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL ownership/permission tests; actual SDK consumption/restart-before-ACK is in AtomicInboxCheckpointTest. */
@QuarkusTest
class OwnedInboxDispatchTest {
    @Inject DataSource dataSource;
    @Inject InstanceStore registry;
    @Inject JdbcEventInbox inbox;
    @Inject JdbcInboxDispatch legacy;
    @Inject JdbcInboxTaskLease leases;
    @Inject JdbcInboxCheckpointProof proof;
    @Inject JdbcOwnedInbox owned;
    record Fixture(InstanceRecord row,InboxWait occurrence,InboxMessage receipt,JdbcInboxTaskLease.Lease lease) { }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private Fixture fixture(Duration ttl,Duration waitDuration) {
        var request=new StartRequest("owned-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),
                new DefinitionRef("agentic/owned-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        row=registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",row.owner());
        row=registry.replace(row.successor(now(),InstanceStatus.WAITING,row.engineInstanceId(),1,null,null),row.version(),"test.waiting",row.owner());
        var wait=new InboxWait(row.tenant(),row.id(),"app",row.engineInstanceId(),"agentic","owned-test","1","do/0/wait",1,
                "orders","order.ready","a".repeat(64),waitDuration);
        legacy.register(wait);
        var receipt=new InboxMessage(row.tenant(),"urn:test:owned",UUID.randomUUID().toString(),wait.eventType(),row.id(),wait.route(),"{\"ok\":true}");
        inbox.receive(receipt,now().plusSeconds(600));
        return new Fixture(row,wait,receipt,leases.acquire(wait.task(),"owner-"+UUID.randomUUID(),ttl).orElseThrow());
    }
    private Fixture fixture() { return fixture(Duration.ofSeconds(30),Duration.ofMinutes(5)); }
    private int attempts(InboxWait wait) throws SQLException {
        try(var c=dataSource.getConnection();var s=c.prepareStatement("select attempts from inbox.delivery where tenant=? and instance_id=? and wait_key=?")) {
            key(s,wait);try(var r=s.executeQuery()) { assertTrue(r.next());return r.getInt(1); }
        }
    }
    private static void key(PreparedStatement s,InboxWait wait) throws SQLException {
        s.setString(1,wait.tenant());s.setString(2,wait.instanceId().value());s.setString(3,wait.key());
    }
    private JdbcOwnedInbox withAudit(io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink audit) {
        return new JdbcOwnedInbox(dataSource,leases,inbox,proof,audit,Clock.systemUTC());
    }
    private void noRowLocks(Fixture f) {
        assertDoesNotThrow(()-> {
            try(var c=dataSource.getConnection()) {
                c.setAutoCommit(false);
                try(var s=c.prepareStatement("select 1 from inbox.delivery where tenant=? and instance_id=? and wait_key=? for update nowait")) {
                    key(s,f.occurrence);try(var r=s.executeQuery()) { assertTrue(r.next()); }
                } finally { c.rollback(); }
            }
        },"audit must run outside the dispatch transaction");
    }
    @Test void oneOwnerEpochGetsOnlyOneAuditedPermissionAndPermissionReturnDoesNotAcknowledge() throws Exception {
        var f=fixture();assertEquals(Optional.of(f.receipt),owned.match(f.lease.claim(),f.occurrence,f.receipt));
        AtomicInteger audited=new AtomicInteger();var service=withAudit(e->{ noRowLocks(f);audited.incrementAndGet(); });
        var permission=service.reserve(f.lease.claim(),f.occurrence).orElseThrow();
        assertEquals(f.receipt,permission.receipt());assertEquals(1,permission.attempt());assertEquals(1,audited.get());
        assertTrue(service.reserve(f.lease.claim(),f.occurrence).isEmpty());assertEquals(1,attempts(f.occurrence));
        assertEquals(JdbcInboxDispatch.Acknowledgement.NO_PROOF,service.acknowledge(f.occurrence));
        assertFalse(proof.consumed(f.occurrence,f.receipt));
    }
    @Test void oldOwnerCannotBindOrReserveAndANewEpochReplaysOnlyTheOriginalReceipt() throws Exception {
        var f=fixture();assertTrue(leases.release(f.lease.claim()));
        var current=leases.acquire(f.occurrence.task(),"new-owner",Duration.ofSeconds(30)).orElseThrow();
        assertThrows(IllegalStateException.class,()->owned.match(f.lease.claim(),f.occurrence,f.receipt));
        assertTrue(inbox.boundSignal(f.occurrence).isEmpty());
        assertEquals(Optional.of(f.receipt),owned.match(current.claim(),f.occurrence,f.receipt));
        var first=owned.reserve(current.claim(),f.occurrence).orElseThrow();
        assertThrows(IllegalStateException.class,()->owned.reserve(f.lease.claim(),f.occurrence));
        assertTrue(leases.release(current.claim()));
        var recovered=leases.acquire(f.occurrence.task(),"recovered-owner",Duration.ofSeconds(30)).orElseThrow();
        var another=new InboxMessage(f.row.tenant(),f.receipt.source(),UUID.randomUUID().toString(),f.occurrence.eventType(),f.row.id(),f.occurrence.route(),"{}");
        inbox.receive(another,now().plusSeconds(600));
        assertTrue(owned.match(recovered.claim(),f.occurrence,another).isEmpty(),"a different predicate-approved candidate cannot replace the bound receipt");
        var replay=owned.reserve(recovered.claim(),f.occurrence).orElseThrow();
        assertEquals(f.receipt,replay.receipt());assertEquals(2,replay.attempt());assertEquals(first.deadline(),replay.deadline());
        assertTrue(owned.reserve(recovered.claim(),f.occurrence).isEmpty());
    }
    @Test void failedAuditLeavesAVisibleAttemptButCannotReturnOrReissuePermissionInTheSameEpoch() throws Exception {
        var f=fixture();owned.match(f.lease.claim(),f.occurrence,f.receipt);
        var failing=withAudit(e->{ noRowLocks(f);throw new IllegalStateException("audit unavailable"); });
        assertThrows(IllegalStateException.class,()->failing.reserve(f.lease.claim(),f.occurrence));
        assertEquals(1,attempts(f.occurrence));assertTrue(owned.reserve(f.lease.claim(),f.occurrence).isEmpty());
        assertTrue(leases.release(f.lease.claim()));
        var next=leases.acquire(f.occurrence.task(),"retry-owner",Duration.ofSeconds(30)).orElseThrow();
        assertEquals(2,owned.reserve(next.claim(),f.occurrence).orElseThrow().attempt());
    }
    @Test void lostReplyAfterTheRealReservationCommitReturnsNoPermissionAndSameEpochCannotRedispatch() throws Exception {
        var f=fixture();owned.match(f.lease.claim(),f.occurrence,f.receipt);AtomicBoolean lost=new AtomicBoolean();
        DataSource failing=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,args)-> {
            Object result=invoke(m,dataSource,args);
            if(!m.getName().equals("getConnection")) return result;
            Connection actual=(Connection)result;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,parameters)-> {
                if(method.getName().equals("commit") && !lost.get()) {
                    boolean dispatched;
                    try(var s=actual.prepareStatement("select state from inbox.delivery where tenant=? and instance_id=? and wait_key=?")) {
                        key(s,f.occurrence);try(var r=s.executeQuery()) { assertTrue(r.next());dispatched=r.getString(1).equals("DISPATCHED"); }
                    }
                    actual.commit();
                    if(dispatched && lost.compareAndSet(false,true)) throw new SQLException("injected lost commit reply","08006");
                    return null;
                }
                return invoke(method,actual,parameters);
            });
        });
        var service=new JdbcOwnedInbox(failing,leases,inbox,proof,e->fail("no audit or permission after unknown commit reply"),Clock.systemUTC());
        assertThrows(StoreUnavailableException.class,()->service.reserve(f.lease.claim(),f.occurrence));
        assertTrue(lost.get());assertEquals(1,attempts(f.occurrence));
        assertTrue(owned.reserve(f.lease.claim(),f.occurrence).isEmpty());
        assertTrue(leases.release(f.lease.claim()));
        var next=leases.acquire(f.occurrence.task(),"commit-recovery-owner",Duration.ofSeconds(30)).orElseThrow();
        var replay=owned.reserve(next.claim(),f.occurrence).orElseThrow();assertEquals(2,replay.attempt());assertEquals(f.receipt,replay.receipt());
    }
    @Test void expiryDuringAuditCannotReleasePermissionAfterTheLastBlockingCall() throws Exception {
        var f=fixture(Duration.ofSeconds(1),Duration.ofMinutes(5));owned.match(f.lease.claim(),f.occurrence,f.receipt);
        var delayed=withAudit(e->{ noRowLocks(f); awaitExpiry("inbox.task_claim","task_digest",f.occurrence.task().digest(),f.occurrence); });
        assertThrows(IllegalStateException.class,()->delayed.reserve(f.lease.claim(),f.occurrence));
        assertEquals(1,attempts(f.occurrence));assertFalse(proof.consumed(f.occurrence,f.receipt));
    }
    @Test void leaseExpiryWhileMatchingWaitsForTheMessageRowRollsBackItsBinding() throws Exception {
        var f=fixture(Duration.ofSeconds(5),Duration.ofMinutes(5));
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try(var s=blocker.prepareStatement("select 1 from inbox.message where tenant=? and source=? and event_id=? for update")) {
                    s.setString(1,f.receipt.tenant());s.setString(2,f.receipt.source());s.setString(3,f.receipt.eventId());
                    try(var r=s.executeQuery()) { assertTrue(r.next()); }
                }
                int pid;
                try(var s=blocker.createStatement();var r=s.executeQuery("select pg_backend_pid()")) { r.next();pid=r.getInt(1); }
                var matching=CompletableFuture.supplyAsync(()->owned.match(f.lease.claim(),f.occurrence,f.receipt));
                assertTimeoutPreemptively(Duration.ofSeconds(5),()-> {
                    while(true) {
                        try(var c=dataSource.getConnection();var s=c.prepareStatement("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))")) {
                            s.setInt(1,pid);try(var r=s.executeQuery()) { r.next();if(r.getBoolean(1)) break; }
                        }
                        Thread.sleep(10);
                    }
                });
                awaitExpiry("inbox.task_claim","task_digest",f.occurrence.task().digest(),f.occurrence);
                blocker.commit();
                var failed=assertThrows(ExecutionException.class,()->matching.get(10,TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class,failed.getCause());
                assertTrue(inbox.boundSignal(f.occurrence).isEmpty());assertEquals(0,attempts(f.occurrence));
                var next=leases.acquire(f.occurrence.task(),"matching-successor",Duration.ofSeconds(30)).orElseThrow();
                assertEquals(Optional.of(f.receipt),owned.match(next.claim(),f.occurrence,f.receipt));
            } finally { blocker.rollback(); }
        }
    }
    @Test void cancellationDuringAuditReturnsNoPermissionOrFalseConsumption() throws Exception {
        var f=fixture();owned.match(f.lease.claim(),f.occurrence,f.receipt);
        var cancelled=withAudit(e->{
            noRowLocks(f);
            registry.replace(f.row.successor(now(),InstanceStatus.CANCEL_REQUESTED,f.row.engineInstanceId(),1,null,null),
                    f.row.version(),"test.cancel-during-audit",f.row.owner());
        });
        assertThrows(IllegalStateException.class,()->cancelled.reserve(f.lease.claim(),f.occurrence));
        assertEquals(1,attempts(f.occurrence));assertFalse(proof.consumed(f.occurrence,f.receipt));
    }
    @Test void expiredUnmatchedWaitCannotBindOrReleaseCallbackPermission() throws Exception {
        var f=fixture(Duration.ofSeconds(30),Duration.ofSeconds(1));
        awaitExpiry("inbox.delivery","wait_key",f.occurrence.key(),f.occurrence);
        assertTrue(owned.match(f.lease.claim(),f.occurrence,f.receipt).isEmpty());
        assertTrue(owned.reserve(f.lease.claim(),f.occurrence).isEmpty());
        assertTrue(inbox.boundSignal(f.occurrence).isEmpty());assertEquals(0,attempts(f.occurrence));
    }
    @Test void legacyReservationAndDirectMutationCannotBypassOwnedEpochPermission() throws Exception {
        var f=fixture();owned.match(f.lease.claim(),f.occurrence,f.receipt);owned.reserve(f.lease.claim(),f.occurrence).orElseThrow();
        try(var claim=legacy.claim(f.occurrence).orElseThrow()) {
            assertThrows(StoreUnavailableException.class,()->legacy.reserve(claim,(w,m)->false));
        }
        assertEquals(1,attempts(f.occurrence));
        for(String change:List.of("claim_epoch=claim_epoch+1","task_digest=null,claim_owner=null,claim_epoch=null","attempts=attempts+1")) {
            try(var c=dataSource.getConnection()) {
                c.setAutoCommit(false);
                try(var s=c.prepareStatement("update inbox.delivery set "+change+" where tenant=? and instance_id=? and wait_key=?")) {
                    key(s,f.occurrence);assertThrows(SQLException.class,s::executeUpdate);
                } finally { c.rollback(); }
            }
        }
    }
    @Test void matchingReservationAndProofReadsUseOnlyOneCheckoutAtATimeAndAuditUsesAClosedTransaction() throws Exception {
        var f=fixture();AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        DataSource single=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,args)-> {
            if(!m.getName().equals("getConnection")) return invoke(m,dataSource,args);
            int n=active.incrementAndGet();maximum.accumulateAndGet(n,Math::max);
            if(n>1) { active.decrementAndGet();throw new SQLException("second concurrent checkout"); }
            Connection actual;
            try { actual=(Connection)invoke(m,dataSource,args); }
            catch(Throwable error) { active.decrementAndGet();throw error; }
            AtomicBoolean closed=new AtomicBoolean();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,parameters)-> {
                if(method.getName().equals("close")) {
                    if(closed.compareAndSet(false,true)) { try { actual.close(); } finally { active.decrementAndGet(); } }return null;
                }
                return invoke(method,actual,parameters);
            });
        });
        var service=new JdbcOwnedInbox(single,new JdbcInboxTaskLease(single,e->{},Clock.systemUTC()),
                new JdbcEventInbox(single,Clock.systemUTC()),new JdbcInboxCheckpointProof(single),e->{
                    try(var c=single.getConnection();var s=c.createStatement();var r=s.executeQuery("select 1")) { assertTrue(r.next()); }
                    catch(SQLException error) { throw new AssertionError(error); }
                },Clock.systemUTC());
        assertEquals(Optional.of(f.receipt),service.match(f.lease.claim(),f.occurrence,f.receipt));
        assertEquals(1,service.reserve(f.lease.claim(),f.occurrence).orElseThrow().attempt());
        assertEquals(JdbcInboxDispatch.Acknowledgement.NO_PROOF,service.acknowledge(f.occurrence));
        assertEquals(0,active.get());assertEquals(1,maximum.get());
    }
    private static Object invoke(Method method,Object target,Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch(InvocationTargetException error) { throw error.getCause(); }
    }
    private void awaitExpiry(String table,String key,String value,InboxWait wait) {
        assertTimeoutPreemptively(Duration.ofSeconds(10),()-> {
            while(true) {
                String expiry=table.equals("inbox.delivery")?"deadline":"expires_at";
                try(var c=dataSource.getConnection();var s=c.prepareStatement("select "+expiry+"<=clock_timestamp() from "+table+" where tenant=? and instance_id=? and "+key+"=?")) {
                    s.setString(1,wait.tenant());s.setString(2,wait.instanceId().value());s.setString(3,value);
                    try(var r=s.executeQuery()) { assertTrue(r.next());if(r.getBoolean(1)) return; }
                }
                Thread.sleep(10);
            }
        });
    }
}
