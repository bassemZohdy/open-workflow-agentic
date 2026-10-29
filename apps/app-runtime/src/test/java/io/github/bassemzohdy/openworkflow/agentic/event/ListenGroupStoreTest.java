package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxListenGroup.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
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

/** Real PostgreSQL selection/order, not predicate truth, callback permission, SDK processing or T13. */
@QuarkusTest
@QuarkusTestResource(value=ListenGroupProposedSchemaResource.class,restrictToAnnotatedClass=true)
class ListenGroupStoreTest {
    static final String A="a".repeat(64),B="b".repeat(64),SNAPSHOT="{\"definition\":\"test-pinned-listen\",\"bindings\":\"test-approved\"}";
    @Inject DataSource data;
    @Inject InstanceStore registry;
    @Inject JdbcInboxTaskLease leases;
    @Inject JdbcEventInbox inbox;
    @Inject JdbcListenGroups groups;
    record Fixture(InstanceRecord row,InboxListenGroup group,InboxTaskClaim claim) { }
    static Instant now() {return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    Fixture fixture(Strategy strategy,List<String> filters,Stop stop,List<String> until) {
        return fixture(strategy,filters,stop,until,SNAPSHOT);
    }
    Fixture fixture(Strategy strategy,List<String> filters,Stop stop,List<String> until,String snapshot) {
        var request=new StartRequest("group-"+UUID.randomUUID(),new Subject(SubjectKind.USER,"alice"),new DefinitionRef("agentic/group-store-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        row=registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",row.owner());
        row=registry.replace(row.successor(now(),InstanceStatus.WAITING,row.engineInstanceId(),1,null,null),row.version(),"test.waiting",row.owner());
        var task=new InboxTask(row.tenant(),row.id(),"app",row.engineInstanceId(),"agentic","group-store-test","1","do/0/awaitEvents",1);
        var spec=new InboxListenGroup(task,strategy,filters,stop,until,stop==Stop.EXPRESSION?A:null,InboxListenGroup.snapshotFingerprint(snapshot));
        var claim=leases.acquire(task,"owner-"+UUID.randomUUID(),Duration.ofSeconds(30)).orElseThrow().claim();
        groups.register(claim,spec,snapshot,Duration.ofMinutes(5));return new Fixture(row,spec,claim);
    }
    InboxMessage receipt(Fixture f,String name) {
        var receipt=new InboxMessage(f.row.tenant(),"urn:test:listen-store",name+"-"+UUID.randomUUID(),"order.ready",f.row.id(),"approved-order","{\"id\":\""+name+"\"}");
        inbox.receive(receipt,now().plusSeconds(600));return receipt;
    }
    JdbcListenGroups service(AuditSink audit) {return new JdbcListenGroups(data,leases,audit,Clock.systemUTC());}
    // Exercise the package-private storage primitive directly; public callers must use a selector result.
    @SuppressWarnings("unchecked")
    static Optional<JdbcListenGroups.Recorded> offer(JdbcListenGroups store,InboxTaskClaim claim,InboxListenGroup group,
                                                       InboxMessage receipt,Set<Slot> matches) {
        try {
            var method=JdbcListenGroups.class.getDeclaredMethod("offer",InboxTaskClaim.class,InboxListenGroup.class,InboxMessage.class,Set.class);
            method.setAccessible(true);return (Optional<JdbcListenGroups.Recorded>)method.invoke(store,claim,group,receipt,matches);
        } catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            throw new IllegalStateException(failure.getCause());
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException(failure);}
    }
    @Test void duplicateAllMembershipsShareOneRetainedReceiptWithoutLegacySignalOrCheckpointProof() throws Exception {
        var f=fixture(Strategy.ALL,List.of(A,A),Stop.NONE,List.of());var receipt=receipt(f,"one");
        var selection=offer(groups,f.claim,f.group,receipt,Set.copyOf(f.group.slots(Role.DATA))).orElseThrow();
        assertEquals(1,selection.sequence());assertEquals(2,selection.memberships().size());
        var binding=groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5));assertTrue(binding.captureComplete());
        assertEquals(0,count(f,"inbox.signal"));assertEquals(0,count(f,"inbox.checkpoint_receipt"));
        assertEquals(List.of(selection),groups.selected(f.claim,f.group,0,128));
        assertEquals(selection,offer(groups,f.claim,f.group,receipt,Set.copyOf(f.group.slots(Role.DATA))).orElseThrow());
        assertEquals(1,count(f,"inbox.listen_item"));
    }
    @Test void publicOfferAcceptsOnlySelectorResultForTheExactReceipt() {
        String filter="{\"with\":{\"type\":\"order.ready\"}}";
        String snapshot="{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"one\":"+filter+"}}}}";
        String fingerprint=CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.event-filter.v1").text(filter).toBytes());
        var f=fixture(Strategy.ONE,List.of(fingerprint),Stop.NONE,List.of(),snapshot);
        String id="public-"+UUID.randomUUID();String json="{\"specversion\":\"1.0\",\"source\":\"urn:test:listen-store\",\"id\":\""+id+"\",\"type\":\"order.ready\"}";
        var message=new InboxMessage(f.row.tenant(),"urn:test:listen-store",id,"order.ready",f.row.id(),"approved-order",json);
        inbox.receive(message,now().plusSeconds(600));var selection=new SdkListenMatchSet(f.group,snapshot).matches(message);
        var changed=new InboxMessage(message.tenant(),message.source(),message.eventId(),message.type(),message.instanceId(),"other-route",message.dataJson());
        assertThrows(IllegalArgumentException.class,()->groups.offer(f.claim,f.group,changed,selection));
        var retained=groups.offer(f.claim,f.group,message,selection).orElseThrow();
        assertEquals(retained,groups.offer(f.claim,f.group,message,selection).orElseThrow());
    }
    @Test void replayRefusesLegacyRowWithoutInventingItsCompleteMatches() throws Exception {
        var f=fixture(Strategy.ANY,List.of(A,B),Stop.NONE,List.of());var message=receipt(f,"legacy");
        var matches=Set.of(f.group.slots(Role.DATA).getFirst());var retained=offer(groups,f.claim,f.group,message,matches).orElseThrow();
        // Test-only simulation of an immutable row retained before the proposed column existed.
        // DDL and trigger changes are confined to this class's disposable database and one transaction.
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try(var statement=c.createStatement()) {
                statement.execute("alter table inbox.listen_item drop constraint listen_item_full_match_fingerprint_check");
                statement.execute("alter table inbox.listen_item disable trigger listen_item_immutable");
                try(var update=c.prepareStatement("update inbox.listen_item set full_match_fingerprint=null where tenant=? and instance_id=? and task_digest=?")) {
                    key(update,f);assertEquals(1,update.executeUpdate());
                }
                statement.execute("alter table inbox.listen_item enable trigger listen_item_immutable");
                statement.execute("alter table inbox.listen_item add constraint listen_item_full_match_fingerprint_check check (full_match_fingerprint is not null and full_match_fingerprint ~ '^[0-9a-f]{64}$') not valid");
                c.commit();
            } catch(Exception failure) {c.rollback();throw failure;}
        }
        assertEquals(List.of(retained),groups.selected(f.claim,f.group,0,128));
        assertThrows(IllegalStateException.class,()->offer(groups,f.claim,f.group,message,matches));
        assertEquals(1,count(f,"inbox.listen_item"));
    }

    @Test void replayRejectsChangedCompleteSetEvenWhenAnyWinnerIsUnchanged() throws Exception {
        var f=fixture(Strategy.ANY,List.of(A,B),Stop.NONE,List.of());var message=receipt(f,"same");
        var slots=f.group.slots(Role.DATA);var first=offer(groups,f.claim,f.group,message,Set.of(slots.getFirst())).orElseThrow();
        assertEquals(first,offer(groups,f.claim,f.group,message,Set.of(slots.getFirst())).orElseThrow());
        assertThrows(IllegalStateException.class,()->offer(groups,f.claim,f.group,message,Set.copyOf(slots)));
        assertEquals(1,count(f,"inbox.listen_item"));
    }
    @Test void selectionCommitOrderSurvivesFreshStoreAndBoundedPagesWithoutSortingByFilterOrdinal() {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());var a=receipt(f,"a");var b=receipt(f,"b");
        var slots=f.group.slots(Role.DATA);
        offer(groups,f.claim,f.group,b,Set.of(slots.get(1)));offer(groups,f.claim,f.group,a,Set.of(slots.get(0)));
        var fresh=service(e->{});var first=fresh.selected(f.claim,f.group,0,1);assertEquals(b,first.getFirst().receipt());
        var second=fresh.selected(f.claim,f.group,first.getFirst().sequence(),1);assertEquals(a,second.getFirst().receipt());
        assertEquals(2,second.getFirst().sequence());assertTrue(fresh.selected(f.claim,f.group,2,1).isEmpty());
        assertThrows(IllegalArgumentException.class,()->fresh.selected(f.claim,f.group,0,129));
        // The future selector must offer in ingress FIFO order; this component preserves its durable selection order.
    }
    @Test void concurrentAnySelectionHasOneDurableWinnerBeforeAnyCallbackExists() throws Exception {
        var f=fixture(Strategy.ANY,List.of(A),Stop.NONE,List.of());var a=receipt(f,"a");var b=receipt(f,"b");
        var slot=Set.copyOf(f.group.slots(Role.DATA));var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var one=workers.submit(()->{start.await();return offer(groups,f.claim,f.group,a,slot);});
            var two=workers.submit(()->{start.await();return offer(groups,f.claim,f.group,b,slot);});start.countDown();
            assertEquals(1,(one.get(10,TimeUnit.SECONDS).isPresent()?1:0)+(two.get(10,TimeUnit.SECONDS).isPresent()?1:0));
        }
        assertEquals(1,groups.selected(f.claim,f.group,0,128).size());assertTrue(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
    }
    @Test void streamReusesDataOrdinalAndUntilMembershipWinsOverOverlappingData() {
        var f=fixture(Strategy.ANY,List.of(A),Stop.ONE,List.of(B));var data=Set.copyOf(f.group.slots(Role.DATA));
        offer(groups,f.claim,f.group,receipt(f,"first"),data);offer(groups,f.claim,f.group,receipt(f,"second"),data);
        assertFalse(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
        var both=new HashSet<>(data);both.addAll(f.group.slots(Role.UNTIL));
        var stop=offer(groups,f.claim,f.group,receipt(f,"stop"),both).orElseThrow();assertEquals(Disposition.UNTIL,stop.disposition());
        assertEquals(3,stop.sequence());assertEquals(Role.UNTIL,stop.memberships().getFirst().role());
        assertTrue(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
        assertTrue(offer(groups,f.claim,f.group,receipt(f,"late"),data).isEmpty());
    }
    @Test void expressionStreamIsNeverMarkedCompleteMerelyFromCapturedInput() {
        var f=fixture(Strategy.ANY,List.of(),Stop.EXPRESSION,List.of());var slot=Set.copyOf(f.group.slots(Role.DATA));
        for(int i=0;i<3;i++) assertTrue(offer(groups,f.claim,f.group,receipt(f,"item-"+i),slot).isPresent());
        assertFalse(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
        assertEquals(3,groups.selected(f.claim,f.group,0,128).size());
    }
    @Test void handoverPreservesWholeSelectionAndHistoricalAttributionButOldOwnerCannotOfferOrRead() {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());var receipt=receipt(f,"first");
        var selection=offer(groups,f.claim,f.group,receipt,Set.of(f.group.slots(Role.DATA).getFirst())).orElseThrow();
        assertTrue(leases.release(f.claim));var next=leases.acquire(f.group.task(),"new-owner",Duration.ofSeconds(30)).orElseThrow().claim();
        assertThrows(IllegalStateException.class,()->groups.selected(f.claim,f.group,0,128));
        assertThrows(IllegalStateException.class,()->offer(groups,f.claim,f.group,receipt,Set.of(f.group.slots(Role.DATA).getFirst())));
        var stored=service(e->{}).load(next).orElseThrow();assertEquals(f.group,stored.group());assertEquals(SNAPSHOT,stored.configurationSnapshot());
        assertEquals(Duration.ofMinutes(5),stored.timeout());
        var recovered=groups.selected(next,stored.group(),0,128).getFirst();assertEquals(selection,recovered);
        assertEquals(f.claim.epoch(),recovered.selectingEpoch());assertNotEquals(next.epoch(),recovered.selectingEpoch());
    }
    @Test void changedSnapshotDeadlineGroupOrReceiptAndForeignScopeAreRefused() {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());var before=groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5));
        assertEquals(before,groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class,()->groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(6)));
        assertThrows(IllegalArgumentException.class,()->groups.register(f.claim,f.group,"{}",Duration.ofMinutes(5)));
        var altered=new InboxListenGroup(f.group.task(),Strategy.ALL,List.of(B,A),Stop.NONE,List.of(),null,f.group.configurationFingerprint());
        assertThrows(IllegalStateException.class,()->groups.register(f.claim,altered,SNAPSHOT,Duration.ofMinutes(5)));
        var original=receipt(f,"original");var changed=new InboxMessage(original.tenant(),original.source(),original.eventId(),original.type(),original.instanceId(),original.correlationKey(),"{}");
        assertThrows(IllegalStateException.class,()->offer(groups,f.claim,f.group,changed,Set.of(f.group.slots(Role.DATA).getFirst())));
        var foreign=new InboxMessage("other-tenant",original.source(),original.eventId(),original.type(),original.instanceId(),original.correlationKey(),original.dataJson());
        assertThrows(IllegalArgumentException.class,()->offer(groups,f.claim,f.group,foreign,Set.copyOf(f.group.slots(Role.DATA))));
        assertTrue(groups.selected(f.claim,f.group,0,128).isEmpty());
    }
    @Test void failedAuditRunsOutsideLocksAndRetainsSelectionWithoutInventingCheckpointOrAnotherSequence() throws Exception {
        var f=fixture(Strategy.ONE,List.of(A),Stop.NONE,List.of());var receipt=receipt(f,"one");
        var failing=service(e->{noLocks(f);throw new IllegalStateException("audit unavailable");});
        assertThrows(IllegalStateException.class,()->offer(failing,f.claim,f.group,receipt,Set.copyOf(f.group.slots(Role.DATA))));
        assertEquals(1,groups.selected(f.claim,f.group,0,128).size());assertEquals(0,count(f,"inbox.checkpoint_receipt"));
        assertEquals(1,offer(groups,f.claim,f.group,receipt,Set.copyOf(f.group.slots(Role.DATA))).orElseThrow().sequence());
        assertEquals(1,count(f,"inbox.listen_item"));
    }
    @Test void unknownCommitReplyRetainsOneSelectionAndRunsNoAuditUntilExplicitReadback() throws Exception {
        var f=fixture(Strategy.ONE,List.of(A),Stop.NONE,List.of());var receipt=receipt(f,"one");var lost=new AtomicBoolean();
        DataSource ambiguous=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,args)->{
            var result=invoke(m,data,args);if(!m.getName().equals("getConnection")) return result;var actual=(Connection)result;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,values)->{
                if(method.getName().equals("commit") && lost.compareAndSet(false,true)) {actual.commit();throw new SQLException("injected lost reply","08006");}
                return invoke(method,actual,values);
            });
        });
        var service=new JdbcListenGroups(ambiguous,leases,e->fail("no audit after an unknown commit reply"),Clock.systemUTC());
        assertThrows(StoreUnavailableException.class,()->offer(service,f.claim,f.group,receipt,Set.copyOf(f.group.slots(Role.DATA))));
        assertTrue(lost.get());assertEquals(1,groups.selected(f.claim,f.group,0,128).size());
        assertEquals(1,offer(groups,f.claim,f.group,receipt,Set.copyOf(f.group.slots(Role.DATA))).orElseThrow().sequence());
        assertEquals(1,count(f,"inbox.listen_item"));
    }
    @Test void directSqlCannotRewriteHistoryOrSkipGroupSequenceOrPinnedDeadline() throws Exception {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());offer(groups,f.claim,f.group,receipt(f,"first"),Set.of(f.group.slots(Role.DATA).getFirst()));
        for(String sql:List.of("update inbox.listen_group set deadline=deadline+interval '1 second'",
                "update inbox.listen_group set last_sequence=last_sequence+2","update inbox.listen_group set capture_complete=true",
                "delete from inbox.listen_group","delete from inbox.listen_item","delete from inbox.listen_member",
                "update inbox.listen_item set receipt_fingerprint='"+B+"'","update inbox.listen_member set ordinal=127")) {
            try(var c=data.getConnection()) {
                c.setAutoCommit(false);
                try(var s=c.prepareStatement(sql+" where tenant=? and instance_id=? and task_digest=?")) {
                    key(s,f);assertEquals("42501",assertThrows(SQLException.class,s::executeUpdate).getSQLState());
                } finally {c.rollback();}
            }
        }
        assertEquals(1,groups.selected(f.claim,f.group,0,128).size());
    }
    @Test void aLateInsertCannotAddAPinnedButUnmatchedSlotToAnAlreadyCommittedReceipt() throws Exception {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());
        offer(groups,f.claim,f.group,receipt(f,"only-a"),Set.of(f.group.slots(Role.DATA).getFirst()));
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("insert into inbox.listen_member(tenant,instance_id,task_digest,sequence,role,ordinal,filter_fingerprint,exclusive_slot) "
                    +"values(?,?,?,1,'DATA',1,?,false)")) {
                key(s,f);s.setString(4,B);
                assertEquals("42501",assertThrows(SQLException.class,s::executeUpdate).getSQLState());
            } finally {c.rollback();}
        }
        var selected=groups.selected(f.claim,f.group,0,128).getFirst();assertEquals(List.of(f.group.slots(Role.DATA).getFirst()),selected.memberships());
        assertFalse(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
    }
    @Test void directSqlCannotFinalizeOnlyPartOfAnAllReceiptAndTheRejectedBatchRollsBack() throws Exception {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());var input=receipt(f,"partial-all");
        rejectPartialAdvance(f,input,1);
        assertTrue(groups.selected(f.claim,f.group,0,128).isEmpty());
        assertEquals(0,count(f,"inbox.listen_item"));assertEquals(0,count(f,"inbox.listen_member"));noLocks(f);
        var accepted=offer(groups,f.claim,f.group,input,Set.copyOf(f.group.slots(Role.DATA))).orElseThrow();
        assertEquals(1,accepted.sequence());assertEquals(2,accepted.memberships().size());
        assertTrue(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
    }
    @Test void allMayOmitAnExclusiveSlotOnlyWhenAnEarlierReceiptAlreadyOwnsIt() {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());
        offer(groups,f.claim,f.group,receipt(f,"first-exclusive"),Set.of(f.group.slots(Role.DATA).getFirst())).orElseThrow();
        var accepted=offer(groups,f.claim,f.group,receipt(f,"remaining-exclusive"),Set.copyOf(f.group.slots(Role.DATA))).orElseThrow();
        assertEquals(2,accepted.sequence());assertEquals(List.of(f.group.slots(Role.DATA).get(1)),accepted.memberships());
        assertTrue(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
    }
    @Test void anyCannotReuseEarlierNonexclusiveMembershipToHideAMatchOnANewReceipt() throws Exception {
        var f=fixture(Strategy.ANY,List.of(A,B),Stop.EXPRESSION,List.of());
        offer(groups,f.claim,f.group,receipt(f,"earlier-any"),Set.of(f.group.slots(Role.DATA).get(1))).orElseThrow();
        var next=receipt(f,"partial-any");rejectPartialAdvance(f,next,2);
        assertEquals(1,groups.selected(f.claim,f.group,0,128).size());noLocks(f);
        var accepted=offer(groups,f.claim,f.group,next,Set.of(f.group.slots(Role.DATA).getFirst())).orElseThrow();
        assertEquals(2,accepted.sequence());assertEquals(List.of(f.group.slots(Role.DATA).getFirst()),accepted.memberships());
        assertFalse(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
    }
    @Test void allUntilMayReuseAnEarlierExclusiveStopMembership() {
        var f=fixture(Strategy.ANY,List.of(A),Stop.ALL,List.of(A,B));
        offer(groups,f.claim,f.group,receipt(f,"first-until"),Set.of(f.group.slots(Role.UNTIL).getFirst())).orElseThrow();
        var accepted=offer(groups,f.claim,f.group,receipt(f,"remaining-until"),Set.copyOf(f.group.slots(Role.UNTIL))).orElseThrow();
        assertEquals(2,accepted.sequence());assertEquals(List.of(f.group.slots(Role.UNTIL).get(1)),accepted.memberships());
        assertTrue(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());
    }
    @Test void aCommittedUnfinalizedReceiptIsHiddenAndCannotBeReplayedAsFinalized() throws Exception {
        var f=fixture(Strategy.ALL,List.of(A,B),Stop.NONE,List.of());var input=receipt(f,"pending-direct-sql");
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try {insertPartialRows(c,f,input,1);c.commit();} finally {c.rollback();}
        }
        assertTrue(groups.selected(f.claim,f.group,0,128).isEmpty());
        assertThrows(IllegalStateException.class,()->offer(groups,f.claim,f.group,input,Set.copyOf(f.group.slots(Role.DATA))));
        assertEquals(1,count(f,"inbox.listen_item"));assertEquals(1,count(f,"inbox.listen_member"));
        assertFalse(groups.register(f.claim,f.group,SNAPSHOT,Duration.ofMinutes(5)).captureComplete());noLocks(f);
    }
    private void insertPartialRows(Connection c,Fixture f,InboxMessage input,long sequence) throws SQLException {
        String matches=f.group.slots(Role.DATA).stream().map(slot->"{\"groupDigest\":\""+slot.groupDigest()+"\",\"role\":\"DATA\",\"ordinal\":"
                +slot.ordinal()+",\"filterFingerprint\":\""+slot.filterFingerprint()+"\"}").collect(java.util.stream.Collectors.joining(",","[","]"));
        try(var s=c.prepareStatement("insert into inbox.listen_item(tenant,instance_id,task_digest,sequence,role,source,event_id,receipt_fingerprint,matched_slots,selecting_owner,selecting_epoch,full_match_fingerprint) values(?,?,?,?,'DATA',?,?,?,?::jsonb,?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')")) {
            key(s,f);s.setLong(4,sequence);s.setString(5,input.source());s.setString(6,input.eventId());s.setString(7,input.fingerprint());
            s.setString(8,matches);s.setString(9,f.claim.owner());s.setLong(10,f.claim.epoch());assertEquals(1,s.executeUpdate());
        }
        var first=f.group.slots(Role.DATA).getFirst();
        try(var s=c.prepareStatement("insert into inbox.listen_member(tenant,instance_id,task_digest,sequence,role,ordinal,filter_fingerprint,exclusive_slot) values(?,?,?,?,'DATA',?,?,false)")) {
            key(s,f);s.setLong(4,sequence);s.setInt(5,first.ordinal());s.setString(6,first.filterFingerprint());assertEquals(1,s.executeUpdate());
        }
    }
    private void rejectPartialAdvance(Fixture f,InboxMessage input,long sequence) throws Exception {
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try {
                insertPartialRows(c,f,input,sequence);
                try(var s=c.prepareStatement("update inbox.listen_group set last_sequence=?,capture_complete=false,write_owner=?,write_epoch=? where tenant=? and instance_id=? and task_digest=?")) {
                    s.setLong(1,sequence);s.setString(2,f.claim.owner());s.setLong(3,f.claim.epoch());s.setString(4,f.row.tenant());
                    s.setString(5,f.row.id().value());s.setString(6,f.group.task().digest());
                    assertEquals("42501",assertThrows(SQLException.class,s::executeUpdate).getSQLState());
                }
            } finally {c.rollback();}
        }
    }
    private void noLocks(Fixture f) {
        assertDoesNotThrow(()->{try(var c=data.getConnection()) {c.setAutoCommit(false);
            try(var s=c.prepareStatement("select 1 from inbox.listen_group where tenant=? and instance_id=? and task_digest=? for update nowait")) {
                key(s,f);try(var r=s.executeQuery()){assertTrue(r.next());}
            } finally {c.rollback();}
        }});
    }
    private int count(Fixture f,String table) throws SQLException {
        try(var c=data.getConnection();var s=c.prepareStatement("select count(*) from "+table+" where tenant=? and instance_id=?")) {
            s.setString(1,f.row.tenant());s.setString(2,f.row.id().value());try(var r=s.executeQuery()){r.next();return r.getInt(1);}
        }
    }
    private static void key(PreparedStatement s,Fixture f) throws SQLException {s.setString(1,f.row.tenant());s.setString(2,f.row.id().value());s.setString(3,f.group.task().digest());}
    private static Object invoke(Method method,Object target,Object[] args) throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}}
}
