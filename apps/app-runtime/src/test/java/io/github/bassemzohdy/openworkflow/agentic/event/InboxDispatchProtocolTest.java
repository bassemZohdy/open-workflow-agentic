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
        assertEquals(Optional.of(event),inbox.matchCandidate(wait,event));
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
    @Test void retainedWaitProfileRoundTripsExactIdentityAndIsTenantScoped() {
        var row=instance();var base=occurrence(row);
        var wait=new InboxWait(base.tenant(),base.instanceId(),base.applicationId(),base.engineInstanceId(),base.namespace(),base.definition(),
                base.version(),"/do/0/é😀",2,base.route(),base.eventType(),base.filterDigest(),Duration.ofSeconds(42,123456789));
        var store=dispatch();store.register(wait);store.register(wait);
        assertEquals(Optional.of(wait),store.findProfile(wait.tenant(),wait.instanceId(),wait.key()));
        assertTrue(store.findProfile("wrong-tenant",wait.instanceId(),wait.key()).isEmpty());
        assertTrue(store.findProfile(wait.tenant(),wait.instanceId(),"other~"+"0".repeat(64)).isEmpty());
    }
    @Test void legacyDigestOnlyRegistrationIsNotGuessedButCanBeRehydratedByExactRegistration() throws Exception {
        var wait=occurrence(instance());
        Instant original=now().plusSeconds(45);
        try(var c=dataSource.getConnection();var q=c.prepareStatement("insert into inbox.delivery "
                +"(tenant,instance_id,wait_key,identity_digest,state,registered_at,deadline) values (?,?,?,?,'REGISTERED',?,?)")) {
            route(q,wait);q.setString(4,wait.digest());q.setTimestamp(5,Timestamp.from(now()));q.setTimestamp(6,Timestamp.from(original));q.executeUpdate();
        }
        var store=dispatch();assertTrue(store.findProfile(wait.tenant(),wait.instanceId(),wait.key()).isEmpty());
        assertEquals(original,store.register(wait));assertEquals(Optional.of(wait),store.findProfile(wait.tenant(),wait.instanceId(),wait.key()));
    }
    @Test void corruptWaitProfileIsRejectedAndRetainedProfileCannotBeChanged() throws Exception {
        var wait=occurrence(instance());var store=dispatch();store.register(wait);
        try(var c=dataSource.getConnection();var q=c.prepareStatement("update inbox.wait_profile set event_type='forged' where tenant=? and instance_id=? and wait_key=?")) {
            route(q,wait);assertThrows(SQLException.class,q::executeUpdate);
        }
        try(var c=dataSource.getConnection();var s=c.createStatement()) {
            s.execute("alter table inbox.wait_profile disable trigger wait_profile_immutable");
            try(var q=c.prepareStatement("update inbox.wait_profile set event_type='forged' where tenant=? and instance_id=? and wait_key=?")) {
                route(q,wait);assertEquals(1,q.executeUpdate());
            } finally {s.execute("alter table inbox.wait_profile enable trigger wait_profile_immutable");}
        }
        assertThrows(IllegalStateException.class,()->store.findProfile(wait.tenant(),wait.instanceId(),wait.key()));
        assertThrows(IllegalStateException.class,()->store.register(wait));
    }
    @Test void failureToRetainTheProfileRollsBackTheWaitRegistration() throws Exception {
        var base=occurrence(instance());
        var wait=new InboxWait(base.tenant(),base.instanceId(),base.applicationId(),base.engineInstanceId(),base.namespace(),base.definition(),
                base.version(),"/do/0/profile-refusal",base.iteration(),base.route(),base.eventType(),base.filterDigest(),base.maxWait());
        try(var c=dataSource.getConnection();var s=c.createStatement()) {
            s.execute("create function inbox.test_reject_profile() returns trigger language plpgsql as $$ begin "
                    +"if new.task_position='/do/0/profile-refusal' then raise exception 'test refusal'; end if; return new; end $$");
            s.execute("create trigger test_reject_profile before insert on inbox.wait_profile for each row execute function inbox.test_reject_profile()");
        }
        try {
            assertThrows(StoreUnavailableException.class,()->dispatch().register(wait));
            try(var c=dataSource.getConnection();var q=c.prepareStatement("select count(*) from inbox.delivery where tenant=? and instance_id=? and wait_key=?")) {
                route(q,wait);try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            }
        } finally {
            try(var c=dataSource.getConnection();var s=c.createStatement()) {
                s.execute("drop trigger test_reject_profile on inbox.wait_profile");s.execute("drop function inbox.test_reject_profile()");
            }
        }
    }
    @Test void restrictedRolesCanUseOnlyTheDocumentedWaitProfileGrants() throws Exception {
        var wait=occurrence(instance());String suffix=UUID.randomUUID().toString().replace("-","");
        String writer="wait_writer_"+suffix,reader="wait_reader_"+suffix,password="local-wait-test";
        boolean writerCreated=false,readerCreated=false;
        try {
            try(var c=dataSource.getConnection();var s=c.createStatement()) {
                s.execute("create role "+writer+" login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '"+password+"'");writerCreated=true;
                s.execute("create role "+reader+" login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '"+password+"'");readerCreated=true;
                s.execute("grant usage on schema task,inbox to "+writer);
                s.execute("grant select,update on task.instance to "+writer);
                s.execute("grant select,insert,update on inbox.delivery to "+writer);
            }
            String url;try(var c=dataSource.getConnection()){url=c.getMetaData().getURL();}
            var writerDs=new org.postgresql.ds.PGSimpleDataSource();writerDs.setURL(url);writerDs.setUser(writer);writerDs.setPassword(password);
            var readerDs=new org.postgresql.ds.PGSimpleDataSource();readerDs.setURL(url);readerDs.setUser(reader);readerDs.setPassword(password);
            var store=new JdbcInboxDispatch(writerDs,e->{});
            assertThrows(StoreUnavailableException.class,()->store.register(wait));
            try(var c=dataSource.getConnection();var q=c.prepareStatement("select count(*) from inbox.delivery where tenant=? and instance_id=? and wait_key=?")) {
                route(q,wait);try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            }
            var root=java.nio.file.Path.of("").toAbsolutePath();
            while(root!=null&&!java.nio.file.Files.isRegularFile(root.resolve("deploy/sql/wait-profile-grants.sql")))root=root.getParent();
            assertNotNull(root,"wait profile grant template not found");
            var sql=java.nio.file.Files.readString(root.resolve("deploy/sql/wait-profile-grants.sql"))
                    .replace("__WAIT_PROFILE_WRITER__",writer).replace("__WAIT_PROFILE_READER__",reader);
            try(var c=dataSource.getConnection();var s=c.createStatement()){s.execute(sql);}
            store.register(wait);assertEquals(Optional.of(wait),store.findProfile(wait.tenant(),wait.instanceId(),wait.key()));
            try(var c=readerDs.getConnection();var s=c.createStatement();var r=s.executeQuery("select count(*) from inbox.wait_profile")){assertTrue(r.next());assertTrue(r.getLong(1)>0);}
            for(var ds:List.of(writerDs,readerDs))try(var c=ds.getConnection();var s=c.createStatement()) {
                for(String command:List.of("update inbox.wait_profile set route=route","delete from inbox.wait_profile","truncate inbox.wait_profile")) {
                    var error=assertThrows(SQLException.class,()->s.execute(command));assertEquals("42501",error.getSQLState());
                }
            }
            try(var c=readerDs.getConnection();var s=c.createStatement()) {
                var error=assertThrows(SQLException.class,()->s.execute("insert into inbox.wait_profile select * from inbox.wait_profile limit 1"));assertEquals("42501",error.getSQLState());
            }
        } finally {
            try(var c=dataSource.getConnection();var s=c.createStatement()) {
                if(readerCreated){s.execute("drop owned by "+reader);s.execute("drop role "+reader);}
                if(writerCreated){s.execute("drop owned by "+writer);s.execute("drop role "+writer);}
            }
        }
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
