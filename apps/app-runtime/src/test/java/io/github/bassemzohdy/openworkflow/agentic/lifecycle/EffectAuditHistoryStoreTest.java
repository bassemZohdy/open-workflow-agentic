package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.count;
import io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.Database;
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
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

@QuarkusTest
class EffectAuditHistoryStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    @Inject Instance<EffectAuditHistory> defaults;
    Database db; PGSimpleDataSource owned; EffectAuditProducer producer; JdbcEffectLedger ledger;
    @BeforeEach void create() throws Exception {
        db=new Database();owned=db.owner;
        try(var c=owned.getConnection()){assertEquals(db.name,scalar(c,"select current_database()"));}
        db.migrate(null);producer=new EffectAuditProducer();ledger=new JdbcEffectLedger(owned,manager,producer);
    }
    @AfterEach void drop() throws Exception {if(db!=null)db.close();}
    Fixture fixture(boolean scoped) {
        var req=new StartRequest("history-"+UUID.randomUUID(),OWNER,new DefinitionRef("agentic/history-fixture","1"),Map.of(),"start");
        var registry=new JdbcInstanceStore(owned);
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),req,now()),"test.start").record();
        registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",OWNER);
        var scope=new EffectScope(row.tenant(),row.id(),OWNER);var command=scope.command("charge","occurrence-1","fixture-target","{}");
        if(scoped)ledger.prepareScoped(scope,command,Duration.ZERO,now());else ledger.prepare(command,Duration.ZERO,now());
        var first=Transactions.inTransaction(owned,c->producer.forKey(c,command.key(),0,128));
        return new Fixture(scope,command,first.isEmpty()?null:first.getFirst());
    }
    EffectAuditHistory service(DataSource ds,IdempotentAuditSink sink) {
        return new EffectAuditHistory(ds,manager,producer,new EffectAuditDelivery(ds,manager,producer,sink));
    }
    EffectAuditHistory service(){return service(owned,new AuditAppendOnce(owned)::appendOnce);}
    List<AuditReceipt> observe(EffectAuditHistory s,Fixture f,int limit){return s.observedHistory(f.scope(),f.command().key(),limit);}
    long number(String query){try{return count(owned,query);}catch(SQLException e){throw new IllegalStateException(e);}}
    void corrupt(String sql) {
        // Privileged defense-in-depth fixture in this UUID-owned disposable database only. Never shared schema.
        Transactions.inTransaction(owned,c->{try(var s=c.createStatement()){s.execute("set local session_replication_role=replica");s.executeUpdate(sql);}return null;});
    }
    String ackTime(long id) throws Exception {try(var c=owned.getConnection()){return scalar(c,"select acked_at::text from effect.audit_delivery where source_event_id=?",id);}}
    @Test void defaultHistoryIsNotInstalled() {assertTrue(defaults.isUnsatisfied());}
    @Test void mixedHistoryRechecksEverySourceAndPreservesOriginalAcknowledgmentTime() throws Exception {
        var f=fixture(true);ledger.markAttempted(f.command().key());var once=new AuditAppendOnce(owned);
        new EffectAuditDelivery(owned,manager,producer,once::appendOnce).deliver(f.id());String original=ackTime(f.id());
        var calls=new AtomicInteger();var s=service(owned,(a,b)->{calls.incrementAndGet();return once.appendOnce(a,b);});
        var first=observe(s,f,128);assertEquals(2,first.size());assertEquals(2,calls.get());
        assertEquals(first,observe(s,f,128));assertEquals(4,calls.get());assertEquals(original,ackTime(f.id()));
        assertEquals(2,number("select count(*) from audit.event"));assertThrows(UnsupportedOperationException.class,()->first.add(first.getFirst()));
    }
    @Test void fullOwnerLegacyMissingAndInvalidBoundsRefuseBeforeCentralCalls() {
        var f=fixture(true);var calls=new AtomicInteger();var s=service(owned,(a,b)->{calls.incrementAndGet();throw new AssertionError("must not reach central");});
        var wrong=new EffectScope(f.scope().tenant(),f.scope().instanceId(),new Subject(SubjectKind.AGENT,OWNER.id()));
        assertThrows(IllegalStateException.class,()->s.observedHistory(wrong,f.command().key(),128));
        var absent=f.scope().command("missing","occurrence-1","fixture-target","{}");
        assertThrows(IllegalStateException.class,()->s.observedHistory(f.scope(),absent.key(),128));
        var legacy=fixture(false);assertThrows(IllegalStateException.class,()->observe(s,legacy,128));
        assertThrows(IllegalArgumentException.class,()->observe(s,f,0));assertThrows(IllegalArgumentException.class,()->observe(s,f,129));
        assertEquals(0,calls.get());
    }
    @Test void originalAndCompensationHistoriesRemainIndependentDataAfterRegistryCompletion() {
        var f=fixture(true);var key=f.command().key();ledger.markAttempted(key);ledger.recordOutcome(key,EffectState.CONFIRMED,null);
        var registry=new JdbcInstanceStore(owned);var row=registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.FAILED,row.engineInstanceId(),1,"fixture-ended",null),row.version(),"test.failed",OWNER);
        var undo=f.scope().command("refund","undo-1","fixture-refund","{}");
        ledger.prepareCompensation(key,undo,Duration.ZERO,now());ledger.markAttempted(undo.key());ledger.recordOutcome(undo.key(),EffectState.CONFIRMED,null);
        ledger.recordOutcome(key,EffectState.COMPENSATED,null);
        var s=service();
        assertEquals(3,s.observedHistory(f.scope(),undo.key(),128).size()); // no cross-key ordering promise
        var original=observe(s,f,128);assertEquals(4,original.size());
        assertEquals(AuditSource.Transition.COMPENSATION,original.getLast().source().transition());
        assertTrue(original.getLast().source().sequence()>original.get(2).source().sequence()+1,"other keys leave legitimate sequence gaps");
        assertEquals(InstanceStatus.FAILED,registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow().status());
    }
    @Test void exactly128FactsPassAnd129RefusesBeforeAnyFurtherCentralCall() {
        var f=fixture(true);for(int i=1;i<128;i++)ledger.requireManualResolution(f.command().key(),"effect.outcome.unknown");
        var calls=new AtomicInteger();var once=new AuditAppendOnce(owned);var s=service(owned,(a,b)->{calls.incrementAndGet();return once.appendOnce(a,b);});
        assertEquals(128,observe(s,f,128).size());assertEquals(128,calls.get());
        ledger.requireManualResolution(f.command().key(),"effect.outcome.unknown");calls.set(0);
        assertThrows(IllegalStateException.class,()->observe(s,f,128));assertEquals(0,calls.get());
        assertEquals(128,number("select count(*) from audit.event"));
    }
    @Test void committedGrowthDuringDeliveryRefusesWithoutDiscardingEarlierAcknowledgments() {
        var f=fixture(true);var once=new AuditAppendOnce(owned);var grow=new AtomicBoolean(true);
        var s=service(owned,(a,b)->{var receipt=once.appendOnce(a,b);if(grow.getAndSet(false))ledger.markAttempted(f.command().key());return receipt;});
        assertThrows(IllegalStateException.class,()->observe(s,f,128));
        assertEquals(1,number("select count(*) from effect.audit_delivery where ack_source_key is not null"));
        assertEquals(2,observe(s,f,128).size());assertEquals(2,number("select count(*) from audit.event"));
    }
    @Test void lostSecondCentralReplyRetainsEarlierAckAndRetryUsesTheSameTwoSources() {
        var f=fixture(true);ledger.markAttempted(f.command().key());var once=new AuditAppendOnce(owned);var calls=new AtomicInteger();
        var s=service(owned,(a,b)->{var receipt=once.appendOnce(a,b);if(calls.incrementAndGet()==2)throw new IllegalStateException("central reply lost");return receipt;});
        assertThrows(IllegalStateException.class,()->observe(s,f,128));
        assertEquals(2,number("select count(*) from audit.event"));assertEquals(1,number("select count(*) from effect.audit_delivery where ack_source_key is null"));
        assertEquals(2,observe(s,f,128).size());assertEquals(2,number("select count(*) from audit.event"));
    }
    @Test void missingSourceRefusesBeforeCentralCalls() {var f=fixture(true);corrupt("delete from effect.audit_source where source_event_id="+f.id());refuseWithoutSink(f);}
    @Test void missingQueueRefusesBeforeCentralCalls() {var f=fixture(true);corrupt("delete from effect.audit_delivery where source_event_id="+f.id());refuseWithoutSink(f);}
    @Test void missingLedgerRefusesBeforeCentralCalls() {var f=fixture(true);corrupt("delete from effect.ledger");refuseWithoutSink(f);}
    @Test void anOrphanedNonInitialSourceAndQueueCannotHideBehindAnEventDrivenScan() {
        var f=fixture(true);ledger.markAttempted(f.command().key());
        corrupt("delete from effect.event where id<>"+f.id());refuseWithoutSink(f);
    }
    @Test void anExtraUnanchoredQueueCannotHideBehindAnEventDrivenScan() {
        var f=fixture(true);
        corrupt("insert into effect.audit_delivery(source_event_id,thread_name,node_id,idempotency_key) select 999999999,thread_name,node_id,idempotency_key from effect.audit_delivery");
        refuseWithoutSink(f);
    }
    @Test void ledgerTailStateMustMatchItsObservedJournal() {var f=fixture(true);corrupt("update effect.ledger set state='CONFIRMED'");refuseWithoutSink(f);}
    void refuseWithoutSink(Fixture f) {
        var calls=new AtomicInteger();var once=new AuditAppendOnce(owned);
        var s=service(owned,(a,b)->{calls.incrementAndGet();return once.appendOnce(a,b);});
        RuntimeException refused=null;
        try{observe(s,f,128);}catch(RuntimeException error){refused=error;}
        assertEquals(0,calls.get(),"incomplete membership must refuse BEFORE trusted central append");
        assertInstanceOf(IllegalStateException.class,refused);
    }
    @Test void alreadyAcknowledgedTimestampCannotChangeDuringTrustedReadback() {
        var f=fixture(true);var once=new AuditAppendOnce(owned);new EffectAuditDelivery(owned,manager,producer,once::appendOnce).deliver(f.id());
        var change=new AtomicBoolean(true);var s=service(owned,(a,b)->{
            var r=once.appendOnce(a,b);if(change.getAndSet(false))corrupt("update effect.audit_delivery set acked_at=acked_at+interval '1 second'");return r;
        });
        assertThrows(IllegalStateException.class,()->observe(s,f,128));assertEquals(1,number("select count(*) from audit.event"));
    }
    @Test void sourceConnectionsAreClosedForActualCentralIoAndAmbientRollbackCannotEraseHistory() throws Exception {
        var f=fixture(true);ledger.markAttempted(f.command().key());var open=new AtomicInteger();var max=new AtomicInteger();
        var single=wrapping(owned,c->{int n=open.incrementAndGet();max.accumulateAndGet(n,Math::max);if(n!=1){c.close();open.decrementAndGet();fail("one slot only");}
            var closed=new AtomicBoolean();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                try{return invoke(m,c,a);}finally{if(m.getName().equals("close")&&closed.compareAndSet(false,true))open.decrementAndGet();}
            });});
        var once=new AuditAppendOnce(single);var s=service(single,(a,b)->{assertEquals(0,open.get());assertNull(assertDoesNotThrow(manager::getTransaction));return once.appendOnce(a,b);});
        caller.begin();try{assertEquals(2,observe(s,f,128).size());assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertEquals(0,open.get());assertEquals(1,max.get());assertEquals(2,number("select count(*) from effect.audit_delivery where ack_source_key is not null"));
    }
    @Test void cancellationBetweenSuccessfulAcknowledgmentsPreservesFlagAndPartialState() {
        var f=fixture(true);ledger.markAttempted(f.command().key());var interrupt=new AtomicBoolean(true);
        var ds=wrapping(owned,c->{var writes=new AtomicBoolean();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement")&&a[0].toString().startsWith("update effect.audit_delivery"))writes.set(true);
            Object r=invoke(m,c,a);if(m.getName().equals("commit")&&writes.get()&&interrupt.getAndSet(false))Thread.currentThread().interrupt();return r;
        });});
        try{assertThrows(CancellationException.class,()->observe(service(ds,new AuditAppendOnce(owned)::appendOnce),f,128));assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
        assertEquals(1,number("select count(*) from audit.event"));assertEquals(1,number("select count(*) from effect.audit_delivery where ack_source_key is null"));
        assertEquals(2,observe(service(),f,128).size());
    }
    @Test void simultaneousObservedHistoriesRetainOneActualCentralEventPerSource() throws Exception {
        var f=fixture(true);ledger.markAttempted(f.command().key());var both=new CyclicBarrier(2);var once=new AuditAppendOnce(owned);
        var s=service(owned,(a,b)->{try{both.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}return once.appendOnce(a,b);});
        var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->observe(s,f,128));var b=pool.submit(()->observe(s,f,128));assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(2,number("select count(*) from audit.event"));
    }
    @Test void anUncommittedWriterIsOutsideTheObservedSnapshotAndAppearsAfterCommit() throws Exception {
        var f=fixture(true);var atCommit=new CountDownLatch(1);var release=new CountDownLatch(1);
        var held=wrapping(owned,c->(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("commit")) {atCommit.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("test release deadline");}
            return invoke(m,c,a);
        }));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var writer=pool.submit(()->new JdbcEffectLedger(held,manager,producer).markAttempted(f.command().key()));
            assertTrue(atCommit.await(5,TimeUnit.SECONDS));
            var reader=pool.submit(()->observe(service(),f,128));
            assertEquals(1,reader.get(5,TimeUnit.SECONDS).size(),"an observed snapshot neither includes nor waits for a later commit");
            release.countDown();writer.get(5,TimeUnit.SECONDS);
            assertEquals(2,observe(service(),f,128).size());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void snapshotIsolationIsTransactionLocalPreservesSerializableAndDoesNotWidenTimeouts() {
        for(boolean serializable:List.of(false,true)) {
            var f=fixture(true);var observations=new ArrayList<String>();
            var ds=wrapping(owned,c->{
                if(serializable)c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try(var s=c.createStatement()){s.execute("set lock_timeout='250ms';set statement_timeout='500ms'");}
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                    if(m.getName().equals("commit"))observations.add(scalar(c,"select current_setting('transaction_isolation')||':'||current_setting('transaction_read_only')||':'||current_setting('lock_timeout')||':'||current_setting('statement_timeout')"));
                    Object result=invoke(m,c,a);
                    if(m.getName().equals("commit"))observations.add(scalar(c,"select current_setting('transaction_isolation')||':'||current_setting('transaction_read_only')||':'||current_setting('lock_timeout')||':'||current_setting('statement_timeout')"));
                    return result;
                });
            });
            assertEquals(1,observe(service(ds,new AuditAppendOnce(owned)::appendOnce),f,128).size());
            assertTrue(observations.contains((serializable?"serializable":"repeatable read")+":on:250ms:500ms"));
            assertTrue(observations.contains((serializable?"serializable":"read committed")+":off:250ms:500ms"));
            assertTrue(observations.stream().allMatch(x->x.endsWith(":250ms:500ms")));
        }
    }
    @Test void resumeFailurePreservesPrimaryAndRefusesSuccessAfterCommittedAcks() throws Exception {
        var f=fixture(true);
        var faulty=(TransactionManager)Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),new Class[]{TransactionManager.class},(p,m,a)->{
            Object result=invoke(m,manager,a);if(m.getName().equals("resume"))throw new SystemException("resume diagnostic");return result;
        });
        var s=new EffectAuditHistory(owned,faulty,producer,new EffectAuditDelivery(owned,manager,producer,new AuditAppendOnce(owned)::appendOnce));
        caller.begin();try {
            var wrong=new EffectScope(f.scope().tenant(),f.scope().instanceId(),new Subject(SubjectKind.AGENT,OWNER.id()));
            var primary=assertThrows(IllegalStateException.class,()->s.observedHistory(wrong,f.command().key(),128));
            assertEquals(1,primary.getSuppressed().length);assertInstanceOf(SystemException.class,primary.getSuppressed()[0]);
            assertEquals(Status.STATUS_ACTIVE,caller.getStatus());
        }finally{caller.rollback();}
        caller.begin();try{assertThrows(StoreUnavailableException.class,()->observe(s,f,128));assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertEquals(1,number("select count(*) from effect.audit_delivery where ack_source_key is not null"));
    }
}
