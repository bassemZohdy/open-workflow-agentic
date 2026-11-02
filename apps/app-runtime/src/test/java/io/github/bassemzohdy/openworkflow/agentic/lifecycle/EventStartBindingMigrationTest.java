package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Proposed V83 only: no ingress, audit authorization, workflow launch, or Flyway activation. */
class EventStartBindingMigrationTest {
    static final Subject OWNER=new Subject(SubjectKind.USER,"owner");
    static final Subject PRODUCER=new Subject(SubjectKind.SERVICE,"producer");
    static StructuredEvent event(int n) {
        var json="{\"specversion\":\"1.0\",\"source\":\"urn:test\",\"id\":\"event-1\",\"type\":\"test\",\"data\":"+n+"}";
        return StructuredEvent.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    static StartRequest request(StructuredEvent event) {
        return new StartRequest("tenant",OWNER,new DefinitionRef("demo/start","1"),Map.of("input",1),
                EventStartBinding.startKey("tenant",event.source(),event.id()));
    }
    static EventStartBinding binding(StructuredEvent event,StartRequest request,Ulid grant) {
        return new EventStartBinding(event,request,PRODUCER,grant,"route","v1");
    }
    static void apply(Connection c) throws Exception {
        try(var in=EventStartBindingMigrationTest.class.getClassLoader().getResourceAsStream("db/proposed/V83__event_start_binding.sql")) {
            assertNotNull(in);c.setAutoCommit(false);
            try(var s=c.createStatement()){s.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));c.commit();}
        }
    }
    @Test void hookCommitsOneBindingAndDuplicateRequiresExactReadback() throws Exception {
        try(var db=new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);try(var c=db.owner.getConnection()){apply(c);}
            var e=event(1);var request=request(e);var grant=UlidGenerator.shared().next();var binding=binding(e,request,grant);
            var store=new JdbcInstanceStore(db.owner);var authority=new InstanceAuthorityStore(db.owner);var now=Instant.now();
            var gate=new CountDownLatch(1);
            try(var workers=Executors.newFixedThreadPool(2)) {
                Callable<InstanceStore.Insertion> insert=()->{gate.await();var candidate=InstanceRecord.starting(UlidGenerator.shared().next(),request,now);
                    return store.insertIfAbsent(candidate,"event.start",OWNER,(c,id)->{
                        authority.insert(c,new InstanceAuthority(id,"tenant",OWNER,grant,null,InstanceAuthority.State.AUTHORIZED,null,now,now));
                        JdbcEventStartBinding.insert(c,id,binding,java.time.Duration.ofSeconds(1));
                    });};
                var a=workers.submit(insert);var b=workers.submit(insert);gate.countDown();
                var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);
                assertNotEquals(first.created(),second.created());assertEquals(first.record().id(),second.record().id());
                try(var c=db.owner.getConnection()) {
                    assertTrue(JdbcEventStartBinding.matches(c,first.record().id(),binding));
                    var retained=JdbcEventStartBinding.read(c,"tenant",binding.startKey()).orElseThrow();
                    assertEquals(first.record().id(),retained.instance());
                    assertEquals(e.canonicalEnvelope(),retained.binding().event().canonicalEnvelope());
                    assertEquals(PRODUCER,retained.binding().producer());
                    assertEquals("route",retained.binding().bindingId());
                    assertEquals("v1",retained.binding().bindingVersion());
                    assertEquals(grant,retained.binding().originalGrant());
                    assertEquals("EventStartReceipt[redacted]",retained.toString());
                    assertTrue(JdbcEventStartBinding.read(c,"other",binding.startKey()).isEmpty());
                    assertFalse(JdbcEventStartBinding.matches(c,first.record().id(),binding(event(2),request,grant)));
                    assertFalse(JdbcEventStartBinding.matches(c,first.record().id(),binding(e,request,UlidGenerator.shared().next())));
                    try(var s=c.createStatement()) {
                        assertThrows(SQLException.class,()->s.executeUpdate("update task.event_start_binding set owner_id='other'"));
                        assertThrows(SQLException.class,()->s.executeUpdate("delete from task.event_start_binding"));
                        // Simulate privileged corruption in the disposable database; the reader must fail closed.
                        s.execute("alter table task.event_start_binding disable trigger user");
                        s.executeUpdate("update task.event_start_binding set event_envelope='{}'");
                        s.execute("alter table task.event_start_binding enable trigger user");
                        assertThrows(SQLException.class,()->JdbcEventStartBinding.read(c,"tenant",binding.startKey()));
                        s.execute("alter table task.event_start_binding disable trigger user");
                        try(var q=c.prepareStatement("update task.event_start_binding set event_envelope=?")) {
                            q.setString(1,e.canonicalEnvelope());q.executeUpdate();
                        }
                        s.execute("alter table task.event_start_binding enable trigger user");
                        s.execute("alter table identity.instance_authority disable trigger user");
                        try(var q=c.prepareStatement("update identity.instance_authority set initial_grant_id=?")) {
                            q.setString(1,UlidGenerator.shared().next().value());q.executeUpdate();
                        }
                        s.execute("alter table identity.instance_authority enable trigger user");
                        assertThrows(SQLException.class,()->JdbcEventStartBinding.read(c,"tenant",binding.startKey()));
                    }
                }
            }
        }
    }
    @Test void terminalExpiredPayloadCanBeRedactedWithoutLosingDuplicateIdentity() throws Exception {
        try(var db=new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);try(var c=db.owner.getConnection()){apply(c);}
            var e=event(1);var request=request(e);var grant=UlidGenerator.shared().next();var binding=binding(e,request,grant);
            var store=new JdbcInstanceStore(db.owner);var authority=new InstanceAuthorityStore(db.owner);var now=Instant.now();
            var candidate=InstanceRecord.starting(UlidGenerator.shared().next(),request,now);
            store.insertIfAbsent(candidate,"event.start",OWNER,(c,id)->{
                authority.insert(c,new InstanceAuthority(id,"tenant",OWNER,grant,null,InstanceAuthority.State.AUTHORIZED,null,now,now));
                JdbcEventStartBinding.insert(c,id,binding,java.time.Duration.ofSeconds(2));
            });
            try(var c=db.owner.getConnection()) {
                c.setAutoCommit(false);
                assertFalse(JdbcEventStartBinding.redact(c,"tenant",candidate.id()),"active work retains its event");
                c.commit();
            }
            var stored=store.findByKey("tenant",request.idempotencyKey()).orElseThrow();
            var terminal=stored.successor(Instant.now(),InstanceStatus.FAILED,null,0,"failed",null);
            store.replace(terminal,stored.version(),"failed",OWNER);
            try(var c=db.owner.getConnection()) {
                c.setAutoCommit(false);
                try(var q=c.prepareStatement("select greatest(0,extract(epoch from retain_until-clock_timestamp())) "
                        + "from task.event_start_binding where instance_id=?")) {
                    q.setString(1,candidate.id().value());
                    try(var r=q.executeQuery()) {
                        assertTrue(r.next());double remaining=r.getDouble(1);
                        if(remaining>0.2) assertFalse(JdbcEventStartBinding.redact(c,"tenant",candidate.id()),"unexpired payload is retained");
                        c.commit();
                        Thread.sleep((long)Math.ceil(remaining*1000)+150);
                    }
                }
                assertFalse(JdbcEventStartBinding.redact(c,"other",candidate.id()));c.commit();
                assertTrue(JdbcEventStartBinding.redact(c,"tenant",candidate.id()));c.rollback();
                assertTrue(JdbcEventStartBinding.read(c,"tenant",binding.startKey()).isPresent(),"rollback restores payload");
                assertTrue(JdbcEventStartBinding.redact(c,"tenant",candidate.id()));c.commit();
                assertThrows(JdbcEventStartBinding.ReceiptRedacted.class,()->JdbcEventStartBinding.read(c,"tenant",binding.startKey()));
                assertTrue(JdbcEventStartBinding.matches(c,candidate.id(),binding));
                assertFalse(JdbcEventStartBinding.matches(c,candidate.id(),binding(event(2),request,grant)));
                assertFalse(JdbcEventStartBinding.redact(c,"tenant",candidate.id()));c.commit();
                try(var s=c.createStatement()) { assertThrows(SQLException.class,()->s.executeUpdate("delete from task.event_start_binding"));c.rollback(); }
            }
        }
    }

    @Test void expiredPayloadIsRetainedWhileInstanceCanStillRun() throws Exception {
        try(var db=new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);try(var c=db.owner.getConnection()){apply(c);}
            var e=event(1);var request=request(e);var grant=UlidGenerator.shared().next();var binding=binding(e,request,grant);
            var store=new JdbcInstanceStore(db.owner);var authority=new InstanceAuthorityStore(db.owner);var now=Instant.now();
            var candidate=InstanceRecord.starting(UlidGenerator.shared().next(),request,now);
            store.insertIfAbsent(candidate,"event.start",OWNER,(c,id)->{
                authority.insert(c,new InstanceAuthority(id,"tenant",OWNER,grant,null,InstanceAuthority.State.AUTHORIZED,null,now,now));
                JdbcEventStartBinding.insert(c,id,binding,java.time.Duration.ofSeconds(1));
            });
            Thread.sleep(1200);
            try(var c=db.owner.getConnection()) {
                c.setAutoCommit(false);
                assertFalse(JdbcEventStartBinding.redact(c,"tenant",candidate.id()));
                assertTrue(JdbcEventStartBinding.read(c,"tenant",binding.startKey()).isPresent());
                try(var s=c.createStatement()) {
                    assertThrows(SQLException.class,()->s.executeUpdate(
                            "update task.event_start_binding set event_envelope=null,redacted_at=clock_timestamp()"));
                    c.rollback();
                }
                assertTrue(JdbcEventStartBinding.matches(c,candidate.id(),binding));c.commit();
            }
        }
    }

    @Test void failedBindingRollsBackRegistryAndAuthority() throws Exception {
        try(var db=new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);try(var c=db.owner.getConnection()){apply(c);}
            var e=event(1);var request=request(e);var grant=UlidGenerator.shared().next();var binding=binding(e,request,grant);
            var store=new JdbcInstanceStore(db.owner);var now=Instant.now();var candidate=InstanceRecord.starting(UlidGenerator.shared().next(),request,now);
            assertThrows(RuntimeException.class,()->store.insertIfAbsent(candidate,"event.start",OWNER,
                    (c,id)->JdbcEventStartBinding.insert(c,id,binding,java.time.Duration.ofSeconds(1)))); // Missing required initial authority.
            assertTrue(store.findByKey("tenant",request.idempotencyKey()).isEmpty());
            var authority=new InstanceAuthorityStore(db.owner);
            assertThrows(RuntimeException.class,()->store.insertIfAbsent(candidate,"event.start",OWNER,(c,id)->{
                authority.insert(c,new InstanceAuthority(id,"tenant",OWNER,grant,null,InstanceAuthority.State.AUTHORIZED,null,now,now));
                JdbcEventStartBinding.insert(c,id,binding,java.time.Duration.ofSeconds(1));
                throw new SQLException("simulated failure after binding");
            }));
            assertTrue(store.findByKey("tenant",request.idempotencyKey()).isEmpty());
            assertTrue(authority.find(candidate.id()).isEmpty());
            try(var c=db.owner.getConnection();var s=c.createStatement();var r=s.executeQuery("select count(*) from task.event_start_binding")) {
                assertTrue(r.next());assertEquals(0,r.getInt(1));
            }
        }
    }
}
