package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.IdempotentAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

/** Actual source/central append-once/local ACK in owned PG databases. DATA only, no target/graph permission. */
@QuarkusTest
class EffectAuditDeliveryStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    @Inject Instance<IdempotentAuditSink> defaultSinks;
    @Inject Instance<EffectAuditDelivery> defaultDeliveries;
    Database db;PGSimpleDataSource owned;EffectAuditProducer producer;JdbcEffectLedger ledger;JdbcInstanceStore registry;
    static final Subject OWNER=new Subject(SubjectKind.USER,"alíce-delivery");
    record Fixture(EffectScope scope,EffectCommand command,EffectAuditProducer.Retained initial) {long id(){return initial.source().sequence();}}
    @BeforeEach void createPrivateStore() throws Exception {
        db=new Database();owned=db.owner;
        try(var c=owned.getConnection()){assertEquals(db.name,scalar(c,"select current_database()"));}
        db.migrate(null);producer=new EffectAuditProducer();ledger=new JdbcEffectLedger(owned,manager,producer);registry=new JdbcInstanceStore(owned);
    }
    @AfterEach void dropPrivateStore() throws Exception {if(db!=null)db.close();}
    static Instant now(){return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    static String scalar(Connection c,String text,Object... args) throws SQLException {
        try(var q=c.prepareStatement(text)){q.setQueryTimeout(10);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}}
    }
    Fixture allocate(boolean prepare) {
        var req=new StartRequest("delivery-"+UUID.randomUUID(),OWNER,new DefinitionRef("agentic/delivery-fixture","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),req,now()),"test.start").record();
        registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",OWNER);
        var scope=new EffectScope(row.tenant(),row.id(),OWNER);var cmd=scope.command("charge","occurrence-1","approved-fixture","{}");
        if(!prepare)return new Fixture(scope,cmd,null);
        ledger.prepareScoped(scope,cmd,Duration.ZERO,now());return new Fixture(scope,cmd,history(cmd.key()).getFirst());
    }
    List<EffectAuditProducer.Retained> history(EffectKey k){return Transactions.inTransaction(owned,c->producer.forKey(c,k,0,128));}
    EffectAuditDelivery delivery(DataSource source,IdempotentAuditSink sink){return new EffectAuditDelivery(source,manager,producer,sink);}
    EffectAuditDelivery delivery(){var once=new AuditAppendOnce(owned);return delivery(owned,once::appendOnce);}
    long pending(){return countUnchecked("select count(*) from effect.audit_delivery where ack_source_key is null");}
    long countUnchecked(String query){try{return count(owned,query);}catch(SQLException e){throw new IllegalStateException(e);}}
    static Object invoke(Method m,Object target,Object[] args) throws Throwable {try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    interface ConnectionWrapper {Connection apply(Connection c) throws SQLException;}
    static DataSource wrapping(DataSource target,ConnectionWrapper wrapper) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{
            Object result=invoke(m,target,a);return m.getName().equals("getConnection")?wrapper.apply((Connection)result):result;
        });
    }
    @Test void defaultDeliveryAndSinkRemainUninstalled() {assertTrue(defaultSinks.isUnsatisfied());assertTrue(defaultDeliveries.isUnsatisfied());}
    @Test void readyAndDirectDeliveryEnforcePredecessorsAndRepeatsUseOriginalCentralReceipt() throws Exception {
        var f=allocate(true);ledger.markAttempted(f.command().key());var facts=history(f.command().key());assertEquals(2,facts.size());
        var calls=new AtomicInteger();var once=new AuditAppendOnce(owned);var relay=delivery(owned,(s,d)->{calls.incrementAndGet();return once.appendOnce(s,d);});
        assertThrows(IllegalArgumentException.class,()->relay.ready(0));assertThrows(IllegalArgumentException.class,()->relay.ready(129));assertThrows(IllegalArgumentException.class,()->relay.deliver(0));
        assertEquals(List.of(f.id()),relay.ready(128));RuntimeException refused=null;
        try{relay.deliver(facts.get(1).source().sequence());}catch(RuntimeException error){refused=error;}
        assertEquals(0,calls.get(),"an earlier pending source must refuse BEFORE central append");assertInstanceOf(IllegalStateException.class,refused);
        var first=relay.deliver(f.id());assertEquals(List.of(facts.get(1).source().sequence()),relay.ready(128));assertEquals(1,relay.drain(128).size());
        String before;try(var c=owned.getConnection()){before=scalar(c,"select acked_at::text from effect.audit_delivery where source_event_id=?",f.id());}
        assertEquals(first,relay.deliver(f.id()));assertEquals(3,calls.get());assertEquals(2,countUnchecked("select count(*) from audit.event"));assertEquals(0,pending());
        try(var c=owned.getConnection()){assertEquals(before,scalar(c,"select acked_at::text from effect.audit_delivery where source_event_id=?",f.id()));}
    }
    @Test void matchingShapedButUnstoredAndActuallyWrongReceiptsCannotAcknowledge() {
        var f=allocate(true);var fake=delivery(owned,(s,d)->new AuditReceipt(s,d.id(),AuditDraftFingerprint.chainOf(d),1,"a".repeat(64),s.contentFingerprint()));
        assertThrows(IllegalStateException.class,()->fake.deliver(f.id()));assertEquals(1,pending());assertEquals(0,countUnchecked("select count(*) from audit.event"));
        var other=allocate(true);var wrong=new AuditAppendOnce(owned).appendOnce(other.initial().source(),other.initial().draft());
        assertThrows(IllegalStateException.class,()->delivery(owned,(s,d)->wrong).deliver(f.id()));assertEquals(2,pending());
    }
    @Test void aStoredSourceWithATamperedReceiptHashOrSequenceCannotAcknowledge() {
        var f=allocate(true);var once=new AuditAppendOnce(owned);var actual=once.appendOnce(f.initial().source(),f.initial().draft());
        String changedHash=(actual.hash().startsWith("a")?"b":"a")+actual.hash().substring(1);
        var changed=List.of(new AuditReceipt(actual.source(),actual.eventId(),actual.chainId(),actual.chainSequence()+1,actual.hash(),actual.contentFingerprint()),
                new AuditReceipt(actual.source(),actual.eventId(),actual.chainId(),actual.chainSequence(),changedHash,actual.contentFingerprint()));
        for(var forged:changed) {
            forged.requireMatches(f.initial().source(),f.initial().draft()); // matching draft/source alone is insufficient
            var error=assertThrows(IllegalStateException.class,()->delivery(owned,(s,d)->forged).deliver(f.id()));
            assertTrue(error.getMessage().contains("matching stored central binding"));assertEquals(1,pending());
        }
        assertEquals(actual,delivery().deliver(f.id()));assertEquals(0,pending());assertEquals(1,countUnchecked("select count(*) from audit.event"));
    }
    @Test void actualCentralCommitThenLostReplyLeavesSameSourcePendingForTrustedReadback() {
        var f=allocate(true);var fail=new AtomicBoolean(true);var once=new AuditAppendOnce(owned);var stored=new AtomicReference<AuditReceipt>();
        var relay=delivery(owned,(s,d)->{var r=once.appendOnce(s,d);stored.set(r);if(fail.getAndSet(false))throw new IllegalStateException("actual central reply lost");return r;});
        assertThrows(IllegalStateException.class,()->relay.deliver(f.id()));assertEquals(1,pending());assertEquals(1,countUnchecked("select count(*) from audit.event"));
        assertEquals(stored.get(),relay.deliver(f.id()));assertEquals(0,pending());assertEquals(1,countUnchecked("select count(*) from audit.event"));
    }
    @Test void actualLocalAcknowledgmentCommitThenLostReplyReopensWithoutAnotherCentralEvent() {
        var f=allocate(true);var fail=new AtomicBoolean(true);
        var fault=wrapping(owned,c->{var writes=new AtomicBoolean();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement")&&a[0].toString().startsWith("update effect.audit_delivery"))writes.set(true);
            Object result=invoke(m,c,a);if(m.getName().equals("commit")&&writes.get()&&fail.getAndSet(false))throw new SQLException("actual ACK reply lost","08006");return result;
        });});
        var once=new AuditAppendOnce(owned);var relay=delivery(fault,once::appendOnce);
        assertThrows(StoreUnavailableException.class,()->relay.deliver(f.id()));assertEquals(0,pending());
        var receipt=relay.deliver(f.id());receipt.requireMatches(f.initial().source(),f.initial().draft());assertEquals(1,countUnchecked("select count(*) from audit.event"));
    }
    @Test void sourceConnectionsCloseBeforeConsumerAndAmbientCallerRollbackCannotEraseReceipts() throws Exception {
        var f=allocate(true);var open=new AtomicInteger();var max=new AtomicInteger();
        var single=wrapping(owned,c->{int n=open.incrementAndGet();max.accumulateAndGet(n,Math::max);if(n!=1){c.close();open.decrementAndGet();fail("one physical slot only");}var closed=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                try{return invoke(m,c,a);}finally{if(m.getName().equals("close")&&closed.compareAndSet(false,true))open.decrementAndGet();}
            });});
        var once=new AuditAppendOnce(single);var relay=delivery(single,(s,d)->{assertEquals(0,open.get());assertNull(assertDoesNotThrow(manager::getTransaction));return once.appendOnce(s,d);});
        caller.begin();try{relay.deliver(f.id());assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertEquals(0,open.get());assertEquals(1,max.get());assertEquals(0,pending());assertEquals(1,countUnchecked("select count(*) from audit.event"));
    }
    @Test void aDelayedLowerIdCommitRemainsReadyAfterAHigherIdWasAcknowledged() throws Exception {
        var low=allocate(false);var atCommit=new CountDownLatch(1);var release=new CountDownLatch(1);var id=new AtomicLong();var pool=Executors.newSingleThreadExecutor();
        var held=wrapping(owned,c->(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("commit")){id.set(Long.parseLong(scalar(c,"select min(id) from effect.event")));atCommit.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("test release deadline");}
            return invoke(m,c,a);
        }));
        try {
            var work=pool.submit(()->new JdbcEffectLedger(held,manager,producer).prepareScoped(low.scope(),low.command(),Duration.ZERO,now()));
            assertTrue(atCommit.await(5,TimeUnit.SECONDS));var high=allocate(true);assertTrue(high.id()>id.get());var relay=delivery();assertEquals(List.of(high.id()),relay.ready(128));relay.deliver(high.id());assertEquals(List.of(),relay.ready(128));
            release.countDown();work.get(5,TimeUnit.SECONDS);assertEquals(List.of(id.get()),relay.ready(128));relay.deliver(id.get());assertEquals(0,pending());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void concurrentDeliveriesUseOneActualCentralEventAndRetainOneAcknowledgment() throws Exception {
        var f=allocate(true);var both=new CyclicBarrier(2);var once=new AuditAppendOnce(owned);var relay=delivery(owned,(s,d)->{try{both.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}return once.appendOnce(s,d);});
        var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->relay.deliver(f.id()));var b=pool.submit(()->relay.deliver(f.id()));assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,countUnchecked("select count(*) from audit.event"));assertEquals(1,countUnchecked("select count(*) from audit.source_binding"));assertEquals(0,pending());
    }
}
