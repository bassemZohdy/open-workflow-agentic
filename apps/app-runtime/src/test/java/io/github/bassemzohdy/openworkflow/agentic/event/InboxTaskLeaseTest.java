package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL ownership/expiry/audit/retention tests; actual SDK rollback cases live in AtomicInboxCheckpointTest. */
@QuarkusTest
class InboxTaskLeaseTest {
    @Inject DataSource dataSource;
    @Inject InstanceStore registry;
    @Inject JdbcInboxTaskLease leases;
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private InstanceRecord instance() {
        var request=new StartRequest("task-lease-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),
                new DefinitionRef("agentic/task-lease-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        row=registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",row.owner());
        return registry.replace(row.successor(now(),InstanceStatus.WAITING,row.engineInstanceId(),1,null,now().plusSeconds(600)),
                row.version(),"test.waiting",row.owner());
    }
    private InboxTask task(InstanceRecord row) {
        return new InboxTask(row.tenant(),row.id(),"app",row.engineInstanceId(),"agentic","task-lease-test","1","do/0/wait",1);
    }

    @Test void liveOwnerRetryDoesNotRearmItsExpiryAndOtherRegistrationsShareThatOwner() throws Exception {
        var task=task(instance()); var first=leases.acquire(task,"owner-a",Duration.ofSeconds(30)).orElseThrow();
        assertEquals(first,leases.acquire(task,"owner-a",Duration.ofSeconds(30)).orElseThrow());
        assertTrue(leases.acquire(task,"owner-b",Duration.ofSeconds(30)).isEmpty());
        assertEquals(first.claim(),leases.renew(first.claim(),Duration.ofSeconds(30)).orElseThrow().claim());
        // No database lock/transaction is retained while partial all-listen waits.
        try(var c=dataSource.getConnection(); var s=c.prepareStatement("select epoch from inbox.task_claim "
                +"where tenant=? and instance_id=? and task_digest=? for update nowait")) {
            key(s,task); try(var r=s.executeQuery()) { assertTrue(r.next()); assertEquals(1,r.getLong(1)); }
        }
    }

    @Test void twoConcurrentOwnersCannotBothAcquireTheSameTask() throws Exception {
        var task=task(instance()); var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        List<CompletableFuture<Optional<JdbcInboxTaskLease.Lease>>> attempts=new ArrayList<>();
        for(String owner:List.of("owner-a","owner-b")) attempts.add(CompletableFuture.supplyAsync(()-> {
            ready.countDown(); try { assertTrue(go.await(5,TimeUnit.SECONDS)); }
            catch(InterruptedException e) { throw new RuntimeException(e); }
            return leases.acquire(task,owner,Duration.ofSeconds(30));
        }));
        assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
        assertEquals(1,(attempts.get(0).get(10,TimeUnit.SECONDS).isPresent()?1:0)
                +(attempts.get(1).get(10,TimeUnit.SECONDS).isPresent()?1:0));
    }

    @Test void releasedOwnerCannotRenewReleaseOrCheckpointAfterTheNextEpoch() {
        var task=task(instance()); var old=leases.acquire(task,"owner-a",Duration.ofSeconds(30)).orElseThrow();
        assertTrue(leases.release(old.claim()));
        var current=leases.acquire(task,"owner-b",Duration.ofSeconds(30)).orElseThrow();
        assertEquals(old.claim().epoch()+1,current.claim().epoch());
        assertTrue(leases.renew(old.claim(),Duration.ofSeconds(30)).isEmpty()); assertFalse(leases.release(old.claim()));
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->leases.requireCurrent(old.claim())));
        QuarkusTransaction.requiringNew().run(()->leases.requireCurrent(current.claim()));
    }

    @Test void expiredOwnerCannotRenewOrCheckpointAndFreshAcquisitionIncrementsItsEpoch() throws Exception {
        var task=task(instance()); var old=leases.acquire(task,"owner-a",Duration.ofMillis(100)).orElseThrow();
        awaitExpiry(task);
        assertTrue(leases.renew(old.claim(),Duration.ofSeconds(30)).isEmpty());
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->leases.requireCurrent(old.claim())));
        var current=leases.acquire(task,"owner-b",Duration.ofSeconds(30)).orElseThrow();
        assertEquals(old.claim().epoch()+1,current.claim().epoch());
    }

    @Test void auditFailureReturnsNoAuthorityAndRetryAuditsTheOriginalLiveEpochBeforeReturningIt() throws Exception {
        var task=task(instance()); Clock clock=Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"),ZoneOffset.UTC);
        var failed=new JdbcInboxTaskLease(dataSource,event->{ throw new IllegalStateException("audit unavailable"); },clock);
        assertThrows(IllegalStateException.class,()->failed.acquire(task,"owner-a",Duration.ofSeconds(30)));
        Instant original;
        try(var c=dataSource.getConnection(); var s=c.prepareStatement("select expires_at from inbox.task_claim where tenant=? and instance_id=? and task_digest=?")) {
            key(s,task); try(var r=s.executeQuery()) { assertTrue(r.next()); original=r.getTimestamp(1).toInstant(); }
        }
        List<AuditEvent> recorded=new ArrayList<>(); var recovered=new JdbcInboxTaskLease(dataSource,recorded::add,clock);
        var lease=recovered.acquire(task,"owner-a",Duration.ofSeconds(30)).orElseThrow();
        assertEquals(1,lease.claim().epoch()); assertEquals(original,lease.expiresAt()); assertEquals(1,recorded.size());
        assertEquals(new Subject(SubjectKind.SERVICE,"system:event-recovery"),recorded.getFirst().actor());
        assertEquals(clock.instant(),recorded.getFirst().occurredAt());
    }

    @Test void cancellationRefusesNewAuthorityAndRenewalButAllowsCleanupOfTheExactOldClaim() {
        var row=instance(); var task=task(row); var old=leases.acquire(task,"owner-a",Duration.ofSeconds(30)).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.CANCEL_REQUESTED,row.engineInstanceId(),1,null,null),
                row.version(),"test.cancel",row.owner());
        assertTrue(leases.acquire(task,"owner-b",Duration.ofSeconds(30)).isEmpty());
        assertTrue(leases.renew(old.claim(),Duration.ofSeconds(30)).isEmpty());
        assertThrows(RuntimeException.class,()->QuarkusTransaction.requiringNew().run(()->leases.requireCurrent(old.claim())));
        assertTrue(leases.release(old.claim()));
    }

    @Test void schemaRefusesLiveTakeoverIdentityChangesEpochSkipsAndRemoval() throws Exception {
        var task=task(instance()); leases.acquire(task,"owner-a",Duration.ofSeconds(30)).orElseThrow();
        for(String change:List.of("owner='owner-b',epoch=epoch+1","task_position='changed'","epoch=epoch+2")) {
            rejected(task,"update inbox.task_claim set "+change+" where tenant=? and instance_id=? and task_digest=?");
        }
        rejected(task,"delete from inbox.task_claim where tenant=? and instance_id=? and task_digest=?");
    }
    private void rejected(InboxTask task,String sql) throws SQLException {
        try(var c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement(sql)) { key(s,task); assertThrows(SQLException.class,s::executeUpdate); }
            finally { c.rollback(); }
        }
    }
    private void awaitExpiry(InboxTask task) {
        assertTimeoutPreemptively(Duration.ofSeconds(5),()-> {
            while(true) {
                try(var c=dataSource.getConnection(); var s=c.prepareStatement("select expires_at<=clock_timestamp() from inbox.task_claim "
                        +"where tenant=? and instance_id=? and task_digest=?")) {
                    key(s,task); try(var r=s.executeQuery()) { assertTrue(r.next()); if(r.getBoolean(1)) return; }
                }
                Thread.sleep(10);
            }
        });
    }
    private static void key(PreparedStatement s,InboxTask task) throws SQLException {
        s.setString(1,task.tenant()); s.setString(2,task.instanceId().value()); s.setString(3,task.digest());
    }
}
