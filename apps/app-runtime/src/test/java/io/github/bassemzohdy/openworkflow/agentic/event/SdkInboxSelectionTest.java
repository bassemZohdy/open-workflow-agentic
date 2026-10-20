package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real JDBC selection/commit boundaries. SDK predicate execution is deliberately outside the store transaction. */
@QuarkusTest
class SdkInboxSelectionTest {
    @Inject JdbcEventInbox inbox;
    @Inject JdbcInboxDispatch dispatch;
    @Inject InstanceStore instances;
    @Inject DataSource dataSource;
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private InstanceRecord instance() {
        var request=new StartRequest("selection-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),
                new DefinitionRef("agentic/selection-test","1.0.0"),Map.of(),"start");
        var row=instances.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        return move(row,InstanceStatus.RUNNING,null);
    }
    private InstanceRecord move(InstanceRecord row,InstanceStatus status,Instant deadline) {
        return instances.replace(row.successor(now(),status,row.engineInstanceId(),row.startAttempts(),null,deadline),
                row.version(),"test.transition",row.owner());
    }
    private InboxWait wait(InstanceRecord row,String position) {
        return new InboxWait(row.tenant(),row.id(),"app","engine","agentic","selection-test","1.0.0",position,1,
                "approval","approval.ready","a".repeat(64),Duration.ofMinutes(5));
    }
    private InboxMessage message(InstanceRecord row,String source,String data) {
        return new InboxMessage(row.tenant(),source,UUID.randomUUID().toString(),"approval.ready",row.id(),"approval",data);
    }
    private void receive(InboxMessage m) { inbox.receive(m,now().plusSeconds(600)); }

    @Test void earlyProducerRouteSurvivesUntilAnInternalOccurrenceIsRegisteredAndWaiting() {
        var row=instance(); var wait=wait(row,"/do/0/wait");
        var early=message(row,"urn:test:approved","{\"ok\":true}"); receive(early);
        assertNotEquals(wait.key(),early.correlationKey(),"a producer never needs the SDK task identity");
        dispatch.register(wait);
        assertTrue(inbox.matchCandidate(wait,early).isEmpty(),"a snapshot cannot wake a running instance");
        move(row,InstanceStatus.WAITING,null);
        var restarted=new JdbcEventInbox(dataSource);
        assertEquals(early,restarted.candidates(wait,0,16).getFirst().message());
        assertEquals(Optional.of(early),restarted.matchCandidate(wait,early));
        try(var claim=dispatch.claim(wait).orElseThrow()) {
            assertEquals(early,dispatch.reserve(claim,(w,m)->false).orElseThrow().receipt());
        }
    }

    @Test void aPredicateRejectedEarlierEventIsNotStolenFromAnotherLogicalWait() {
        var row=move(instance(),InstanceStatus.WAITING,null);
        var a=message(row,"urn:test:a","{\"result\":\"a\"}");
        var b=message(row,"urn:test:b","{\"result\":\"b\"}"); receive(a); receive(b);
        var waitB=wait(row,"/do/0/branchB"); dispatch.register(waitB);
        var selected=inbox.candidates(waitB,0,16).stream().map(JdbcEventInbox.Candidate::message)
                .filter(m->m.source().equals("urn:test:b")).findFirst().orElseThrow();
        assertEquals(Optional.of(b),inbox.matchCandidate(waitB,selected));
        var waitA=wait(row,"/do/0/branchA"); dispatch.register(waitA);
        assertEquals(List.of(a),inbox.candidates(waitA,0,16).stream().map(JdbcEventInbox.Candidate::message).toList());
        assertEquals(Optional.of(a),inbox.matchCandidate(waitA,a));
        assertTrue(inbox.matchCandidate(waitB,a).isEmpty(),"a later predicate result cannot authorize a substituted receipt");
        assertEquals(Optional.of(b),inbox.boundSignal(waitB),"restore can re-evaluate the exact original receipt");
        assertEquals(Optional.of(b),inbox.matchCandidate(waitB,b));
    }

    @Test void aSnapshotCannotOverrideCancellationOrAChangedWaitDeadline() {
        for(boolean cancel:new boolean[]{false,true}) {
            var row=move(instance(),InstanceStatus.WAITING,null); var wait=wait(row,"/do/0/wait"); dispatch.register(wait);
            var event=message(row,"urn:test:approved","{}"); receive(event);
            var snapshot=inbox.candidates(wait,0,1).getFirst().message();
            move(row,cancel?InstanceStatus.CANCEL_REQUESTED:InstanceStatus.WAITING,cancel?null:now().minusSeconds(1));
            assertTrue(inbox.matchCandidate(wait,snapshot).isEmpty());
        }
    }

    @Test void retentionAndContentAreRecheckedWhenCommittingASnapshot() throws Exception {
        var row=move(instance(),InstanceStatus.WAITING,null); var wait=wait(row,"/do/0/wait"); dispatch.register(wait);
        var event=message(row,"urn:test:approved","{\"approved\":true}"); receive(event);
        var snapshot=inbox.candidates(wait,0,1).getFirst().message();
        var changed=new InboxMessage(event.tenant(),event.source(),event.eventId(),event.type(),event.instanceId(),event.correlationKey(),"{}");
        assertThrows(IllegalStateException.class,()->inbox.matchCandidate(wait,changed));
        // Age ONLY this private fixture row after the read; an expired snapshot must not authorize a match.
        try(var c=dataSource.getConnection();var s=c.prepareStatement("update inbox.message set retain_until=clock_timestamp()-interval '1 second' "
                + "where tenant=? and source=? and event_id=?")) {
            s.setString(1,event.tenant()); s.setString(2,event.source()); s.setString(3,event.eventId()); assertEquals(1,s.executeUpdate());
        }
        assertTrue(inbox.matchCandidate(wait,snapshot).isEmpty());
        assertTrue(inbox.candidates(wait,0,16).isEmpty());
    }

    @Test void oneReceiptCanBeBoundByOnlyOneOfTwoConcurrentOccurrences() throws Exception {
        var row=move(instance(),InstanceStatus.WAITING,null); var a=wait(row,"/a"); var b=wait(row,"/b");
        dispatch.register(a); dispatch.register(b); var event=message(row,"urn:test:approved","{}"); receive(event);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var left=workers.submit(()->{start.await();return inbox.matchCandidate(a,event).isPresent();});
            var right=workers.submit(()->{start.await();return inbox.matchCandidate(b,event).isPresent();});
            start.countDown(); assertEquals(1,(left.get(10,TimeUnit.SECONDS)?1:0)+(right.get(10,TimeUnit.SECONDS)?1:0));
        }
    }

    @Test void candidatePagesAreBoundedAndOrderedWithoutHidingRejectedReceiptsFromAnotherWait() {
        var row=instance(); var wait=wait(row,"/wait");
        var first=message(row,"urn:test:first","{}"); var second=message(row,"urn:test:second","{}"); var third=message(row,"urn:test:third","{}");
        receive(first);receive(second);receive(third);
        var page=inbox.candidates(wait,0,2); assertEquals(List.of(first,second),page.stream().map(JdbcEventInbox.Candidate::message).toList());
        var tail=inbox.candidates(wait,page.getLast().sequence(),2); assertEquals(List.of(third),tail.stream().map(JdbcEventInbox.Candidate::message).toList());
        assertEquals(3,inbox.candidates(wait,0,128).size(),"read-only selection does not consume rejected candidates");
    }
}
