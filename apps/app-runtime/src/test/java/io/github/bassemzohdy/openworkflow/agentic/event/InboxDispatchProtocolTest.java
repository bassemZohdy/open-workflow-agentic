package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL protocol tests. Proof doubles test the port contract; these are not SDK consumption/T13 proof. */
@QuarkusTest
class InboxDispatchProtocolTest {
    @Inject DataSource dataSource;
    @Inject InstanceStore instances;
    @Inject JdbcEventInbox inbox;
    private static final InboxCheckpointProof ABSENT = (occurrence, receipt) -> false;
    private final List<AuditEvent> audits = new CopyOnWriteArrayList<>();
    private JdbcInboxDispatch dispatch() { return new JdbcInboxDispatch(dataSource, audits::add); }

    private InstanceRecord instance() {
        var request = new StartRequest("dispatch-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),
                new DefinitionRef("agentic/dispatch-test","1"),Map.of(),"start");
        var row = instances.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        return move(move(row,InstanceStatus.RUNNING,null),InstanceStatus.WAITING,null);
    }
    private InstanceRecord move(InstanceRecord row, InstanceStatus status, Instant deadline) {
        return instances.replace(row.successor(now(),status,row.engineInstanceId(),row.startAttempts(),null,deadline),
                row.version(),"test.transition",row.owner());
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private InboxWait occurrence(InstanceRecord row) {
        return new InboxWait(row.tenant(),row.id(),"app","engine-"+row.id().value(),"agentic","dispatch-test","1",
                "/do/0/wait",1,"orders","order.ready","a".repeat(64),Duration.ofMinutes(5));
    }
    private InboxMessage match(InstanceRecord row, InboxWait wait) {
        var event = new InboxMessage(row.tenant(),"urn:test:dispatch",UUID.randomUUID().toString(),wait.eventType(),
                row.id(),wait.route(),"{\"ok\":true}");
        inbox.receive(event,now().plusSeconds(600));
        assertEquals(Optional.of(event),inbox.match(row.tenant(),row.id(),wait.route(),wait.eventType()));
        return event;
    }
    private String state(InboxWait wait) {
        return Transactions.inTransaction(dataSource,c -> {
            try (var s=c.prepareStatement("select state from inbox.delivery where tenant=? and instance_id=? and wait_key=?")) {
                route(s,wait); try(var r=s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
            }
        });
    }
    private static void route(PreparedStatement s, InboxWait wait) throws SQLException {
        s.setString(1,wait.tenant()); s.setString(2,wait.instanceId().value()); s.setString(3,wait.key());
    }

    @Test void restartReplaysTheSameReceiptButCheckpointBeforeAckPreventsAnotherDispatch() {
        var row=instance(); var wait=occurrence(row); var first=dispatch(); first.register(wait);
        var event=match(row,wait);
        try(var claim=first.claim(wait).orElseThrow()) {
            var attempt=first.reserve(claim,ABSENT).orElseThrow(); assertEquals(event,attempt.receipt()); assertEquals(1,attempt.attempt());
            assertEquals(JdbcInboxDispatch.Acknowledgement.NO_PROOF,first.acknowledge(claim,ABSENT));
            assertThrows(IllegalStateException.class,()->first.reserve(claim,ABSENT));
        }
        var restored=dispatch();
        try(var claim=restored.claim(wait).orElseThrow()) {
            var attempt=restored.reserve(claim,ABSENT).orElseThrow(); assertEquals(event,attempt.receipt()); assertEquals(2,attempt.attempt());
        }
        var afterCheckpoint=dispatch();
        try(var claim=afterCheckpoint.claim(wait).orElseThrow()) {
            assertTrue(afterCheckpoint.reserve(claim,(w,m)->w.equals(wait)&&m.equals(event)).isEmpty());
        }
        assertEquals("ACKNOWLEDGED",state(wait));
        var again=dispatch();
        try(var claim=again.claim(wait).orElseThrow()) { assertTrue(again.reserve(claim,ABSENT).isEmpty()); }
        assertEquals(2,audits.stream().filter(e->e.action().equals("event.dispatch.attempt")).count());
        assertTrue(audits.stream().allMatch(e->e.actor().equals(new Subject(SubjectKind.SERVICE,"system:event-recovery"))));
    }

    @Test void oneReplicaOwnsTheClaimUntilExplicitRelease() {
        var wait=occurrence(instance()); var one=dispatch(); var two=dispatch(); one.register(wait);
        try(var held=one.claim(wait).orElseThrow()) { assertTrue(two.claim(wait).isEmpty()); }
        try(var acquired=two.claim(wait).orElseThrow()) { assertNotNull(acquired); }
    }

    @Test void anotherPinnedVersionOrEngineInstanceCannotRegister() {
        var row=instance(); var wait=occurrence(row); var dispatch=dispatch();
        var wrongVersion=new InboxWait(wait.tenant(),wait.instanceId(),wait.applicationId(),wait.engineInstanceId(),
                wait.namespace(),wait.definition(),"2",wait.taskPosition(),wait.iteration(),wait.route(),wait.eventType(),
                wait.filterDigest(),wait.maxWait());
        assertThrows(IllegalStateException.class,()->dispatch.register(wrongVersion));
        row=instances.replace(row.successor(now(),row.status(),"pinned-engine",row.startAttempts(),null,null),
                row.version(),"test.engine",row.owner());
        assertThrows(IllegalStateException.class,()->dispatch.register(wait));
    }

    @Test void cancellationAfterMatchingWinsBeforeReservation() {
        var row=instance(); var wait=occurrence(row); var dispatch=dispatch(); dispatch.register(wait); match(row,wait);
        move(row,InstanceStatus.CANCEL_REQUESTED,null);
        try(var claim=dispatch.claim(wait).orElseThrow()) { assertTrue(dispatch.reserve(claim,ABSENT).isEmpty()); }
        assertEquals("CANCELLED",state(wait)); assertTrue(audits.isEmpty());
    }

    @Test void currentRegistryDeadlineIsRecheckedAfterMatching() {
        var row=instance(); var wait=occurrence(row); var dispatch=dispatch(); dispatch.register(wait); match(row,wait);
        move(row,InstanceStatus.WAITING,now().minusSeconds(1));
        try(var claim=dispatch.claim(wait).orElseThrow()) { assertTrue(dispatch.reserve(claim,ABSENT).isEmpty()); }
        assertEquals("EXPIRED",state(wait)); assertTrue(audits.isEmpty());
    }

    @Test void repeatedRegistrationNeverExtendsTheFirstClampedDeadlineEvenAfterExpiry() {
        var row=instance(); var registryDeadline=now().plusSeconds(20);
        row=move(row,InstanceStatus.WAITING,registryDeadline);
        var wait=occurrence(row); assertEquals(registryDeadline,dispatch().register(wait));
        move(row,InstanceStatus.WAITING,now().minusSeconds(1));
        assertEquals(registryDeadline,dispatch().register(wait));
    }

    @Test void auditFailureReleasesNoPermissionAndLeavesARecoverableAttempt() {
        var row=instance(); var wait=occurrence(row); var broken=new JdbcInboxDispatch(dataSource,e->{throw new IllegalStateException("audit offline");});
        broken.register(wait); var event=match(row,wait);
        try(var claim=broken.claim(wait).orElseThrow()) { assertThrows(IllegalStateException.class,()->broken.reserve(claim,ABSENT)); }
        assertEquals("DISPATCHED",state(wait)); var restored=dispatch();
        try(var claim=restored.claim(wait).orElseThrow()) {
            var next=restored.reserve(claim,ABSENT).orElseThrow(); assertEquals(2,next.attempt()); assertEquals(event,next.receipt());
        }
    }

    @Test void cancelledRowLockWinnerPreventsAWaitingDispatcher() throws Exception {
        var row=instance(); var wait=occurrence(row); var dispatch=dispatch(); dispatch.register(wait); match(row,wait);
        try(var claim=dispatch.claim(wait).orElseThrow(); var worker=Executors.newSingleThreadExecutor(); var c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("update task.instance set status='CANCEL_REQUESTED',version=version+1 where tenant=? and id=?")) {
                s.setString(1,row.tenant()); s.setString(2,row.id().value()); assertEquals(1,s.executeUpdate());
            }
            var entered=new CountDownLatch(1);
            var pending=worker.submit(()->{entered.countDown(); return dispatch.reserve(claim,ABSENT);});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->pending.get(100,TimeUnit.MILLISECONDS));
            c.commit(); assertTrue(pending.get(5,TimeUnit.SECONDS).isEmpty());
        }
        assertEquals("CANCELLED",state(wait));
    }

    @Test void schemaRefusesRearmingOrDeletingAcknowledgedTombstones() {
        var row=instance(); var wait=occurrence(row); var dispatch=dispatch(); dispatch.register(wait); var event=match(row,wait);
        try(var claim=dispatch.claim(wait).orElseThrow()) {
            dispatch.reserve(claim,ABSENT).orElseThrow();
            assertEquals(JdbcInboxDispatch.Acknowledgement.ACKNOWLEDGED,dispatch.acknowledge(claim,(w,m)->m.equals(event)));
        }
        for(String sql:List.of("delete from inbox.delivery where tenant=? and instance_id=? and wait_key=?",
                "update inbox.delivery set deadline=deadline+interval '1 hour' where tenant=? and instance_id=? and wait_key=?",
                "update inbox.delivery set state='DISPATCHED',attempts=attempts+1,acknowledged_at=null where tenant=? and instance_id=? and wait_key=?")) {
            assertThrows(StoreUnavailableException.class,()->Transactions.inTransaction(dataSource,c->{
                try(var s=c.prepareStatement(sql)) { route(s,wait); s.executeUpdate(); return null; }
            }));
        }
        assertEquals("ACKNOWLEDGED",state(wait));
    }
}
