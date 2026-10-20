package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.IdempotentAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Real source/queue transactions in an owned fresh database. Central receipt DATA fixtures use existing append
 * plus a separate binding insert; they do NOT implement or prove an atomic/idempotent appendOnce consumer. */
@QuarkusTest @TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class EffectAuditProducerStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    PGSimpleDataSource owned;
    EffectAuditTestDatabase database;
    JdbcInstanceStore registry;
    EffectAuditProducer producer;
    JdbcEffectLedger ledger;
    static final Subject OWNER=new Subject(SubjectKind.USER,"alíce-𝄞");
    record Fixture(EffectScope scope,EffectCommand command) { }
    static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    @BeforeAll void createOwnedDatabase() throws Exception {
        database=EffectAuditTestDatabase.open();owned=database.dataSource;
        registry=new JdbcInstanceStore(owned);producer=new EffectAuditProducer();ledger=new JdbcEffectLedger(owned,manager,producer);
    }
    @AfterAll void dropOnlyOwnedDatabase() throws Exception {if(database!=null)database.close();}
    static void sql(Connection c,String text) throws SQLException {EffectAuditTestDatabase.sql(c,text);}
    Fixture fixture() {
        var req=new StartRequest("effect-source-"+UUID.randomUUID(),OWNER,new DefinitionRef("agentic/effect-source-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),req,now()),"test.start").record();
        registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",OWNER);
        var scope=new EffectScope(row.tenant(),row.id(),OWNER);
        return new Fixture(scope,scope.command("charge","item/é/1","approved-charge","{\"amount\":10}"));
    }
    JdbcEffectLedger.Prepared prepare(Fixture f){return ledger.prepareScoped(f.scope(),f.command(),Duration.ZERO,now());}
    List<EffectAuditProducer.Retained> history(Fixture f) {
        return Transactions.inTransaction(owned,c->producer.forKey(c,f.command().key(),0,128));
    }
    long count(Fixture f,String table) throws SQLException {
        boolean source=table.equals("audit_source");
        String query=source?"select count(*) from effect.audit_source a join effect.event e on e.id=a.source_event_id where e.thread_name=? and e.node_id=? and e.idempotency_key=?":
                "select count(*) from effect."+table+" where thread_name=? and node_id=? and idempotency_key=?";
        try(var c=owned.getConnection();var q=c.prepareStatement(query)) {
            var key=f.command().key();q.setString(1,key.threadId().value());q.setString(2,key.nodeId());q.setString(3,key.idempotencyKey());
            try(var r=q.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    @Test void firstIntentScopeAndOriginalDraftCommitTogetherAndExactRetryReopensSameSource() throws Exception {
        var f=fixture();var first=prepare(f);var saved=history(f);assertEquals(1,saved.size());
        assertEquals(AuditSource.Transition.INTENT,saved.getFirst().source().transition());
        assertEquals(first,prepare(f));
        var reopened=new JdbcEffectLedger(owned,manager,new EffectAuditProducer());
        assertEquals(first,reopened.prepareScoped(f.scope(),f.command(),Duration.ZERO,now()));assertEquals(saved,history(f));
        assertEquals(1,count(f,"ledger"));assertEquals(1,count(f,"event"));assertEquals(1,count(f,"audit_scope"));assertEquals(1,count(f,"audit_source"));assertEquals(1,count(f,"audit_delivery"));
        assertEquals(f.command().key().threadId(),f.scope().threadId());
        var value=saved.getFirst();assertEquals(AuditDraftFingerprint.of(value.draft()),value.source().contentFingerprint());
        try(var c=owned.getConnection();var q=c.prepareStatement("select occurred_at from effect.event where id=?")) {
            q.setLong(1,value.source().sequence());try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(value.draft().occurredAt(),r.getTimestamp(1).toInstant());}
        }
    }
    @Test void dispatchUnknownOutcomeAndCompensationFactsKeepOriginalProvenanceAfterRegistryEnds() throws Exception {
        var f=fixture();prepare(f);var key=f.command().key();ledger.markAttempted(key);
        ledger.requireManualResolution(key,"effect.outcome.unknown");ledger.recordOutcome(key,EffectState.CONFIRMED,"not-for-central-audit");
        var row=registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.FAILED,row.engineInstanceId(),1,"test-ended",null),row.version(),"test.failed",OWNER);
        var undo=f.scope().command("refund","item/é/undo","approved-refund","{\"amount\":10}");
        ledger.prepareCompensation(key,undo,Duration.ZERO,now());ledger.markAttempted(undo.key());ledger.recordOutcome(undo.key(),EffectState.CONFIRMED,null);
        ledger.recordOutcome(key,EffectState.COMPENSATED,null);
        var values=history(f);assertEquals(List.of(AuditSource.Transition.INTENT,AuditSource.Transition.DISPATCH,AuditSource.Transition.UNKNOWN,
                AuditSource.Transition.OUTCOME,AuditSource.Transition.COMPENSATION),values.stream().map(v->v.source().transition()).toList());
        assertTrue(values.stream().noneMatch(v->v.draft().attributes().containsValue("not-for-central-audit")));
        var undoValues=Transactions.inTransaction(owned,c->producer.forKey(c,undo.key(),0,128));assertEquals(3,undoValues.size());
        assertEquals(values.getFirst().source().scopeFingerprint(),undoValues.getFirst().source().scopeFingerprint());
        assertNotEquals(values.getFirst().source().keyFingerprint(),undoValues.getFirst().source().keyFingerprint());
    }
    @Test void legacyRowsAndWrongFullOwnerKindAreNeverUpgradedFromTheSameWs1Key() throws Exception {
        var f=fixture();var old=new JdbcEffectLedger(owned);old.prepare(f.command(),Duration.ZERO,now());
        assertThrows(IllegalStateException.class,()->prepare(f));assertEquals(0,count(f,"audit_scope"));assertEquals(0,count(f,"audit_source"));
        var another=fixture();var wrong=new EffectScope(another.scope().tenant(),another.scope().instanceId(),new Subject(SubjectKind.AGENT,OWNER.id()));
        assertEquals(another.scope().threadId(),wrong.threadId());
        assertThrows(IllegalStateException.class,()->ledger.prepareScoped(wrong,another.command(),Duration.ZERO,now()));
        assertEquals(0,count(another,"ledger"));
    }
    @ParameterizedTest @ValueSource(strings={"audit_source","audit_delivery"})
    void aSourceOrQueueInsertFailureRollsBackIntentScopeJournalAndDraft(String table) throws Exception {
        var f=fixture();String name="gpt_source_fault_"+UUID.randomUUID().toString().replace("-","");assertTrue(name.matches("gpt_source_fault_[0-9a-f]{32}"));
        try(var c=owned.getConnection()) {
            sql(c,"create function effect."+name+"() returns trigger language plpgsql as $$ begin if exists(select 1 from effect.event e join effect.audit_scope s using(thread_name,node_id,idempotency_key) where e.id=new.source_event_id and s.registry_instance_id='"+f.scope().instanceId().value()+"') then raise exception 'injected source/queue failure'; end if; return new; end $$");
            sql(c,"create trigger "+name+" before insert on effect."+table+" for each row execute function effect."+name+"()");
        }
        try {
            assertThrows(StoreUnavailableException.class,()->prepare(f));
            for(var factTable:List.of("ledger","event","audit_scope","audit_source","audit_delivery"))assertEquals(0,count(f,factTable));
        } finally {
            try(var c=owned.getConnection()){sql(c,"drop trigger "+name+" on effect."+table);sql(c,"drop function effect."+name+"()");}
        }
        prepare(f);assertEquals(1,history(f).size());
    }
    static DataSource loseCommitReply(DataSource delegate,AtomicBoolean armed) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value;
            try{value=m.invoke(delegate,a);}catch(InvocationTargetException e){throw e.getCause();}
            if(!m.getName().equals("getConnection"))return value;
            var connection=(Connection)value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(q,cm,ca)->{
                Object result;try{result=cm.invoke(connection,ca);}catch(InvocationTargetException e){throw e.getCause();}
                if(cm.getName().equals("commit") && armed.compareAndSet(true,false))throw new SQLException("injected lost reply AFTER actual commit");
                return result;
            });
        });
    }
    @Test void actualCommitReplyLossRetainsSameDraftAcrossFreshComponentRetry() throws Exception {
        var f=fixture();var armed=new AtomicBoolean(true);
        var uncertain=new JdbcEffectLedger(loseCommitReply(owned,armed),manager,new EffectAuditProducer());
        assertThrows(StoreUnavailableException.class,()->uncertain.prepareScoped(f.scope(),f.command(),Duration.ZERO,now()));
        assertFalse(armed.get());var saved=history(f);assertEquals(1,saved.size());
        new JdbcEffectLedger(owned,manager,new EffectAuditProducer()).prepareScoped(f.scope(),f.command(),Duration.ZERO,now());
        assertEquals(saved,history(f));assertEquals(1,count(f,"event"));assertEquals(1,count(f,"audit_source"));
    }
    @Test void aWriterWithoutTheProducerCannotCommitAScopedJournalFact() throws Exception {
        var f=fixture();prepare(f);var old=new JdbcEffectLedger(owned);
        assertThrows(StoreUnavailableException.class,()->old.markAttempted(f.command().key()));
        assertFalse(ledger.prepareScoped(f.scope(),f.command(),Duration.ZERO,now()).attempted());assertEquals(1,count(f,"event"));
        ledger.markAttempted(f.command().key());assertEquals(2,history(f).size());
    }
    @Test void concurrentExactPreparationsCommitOneInitialFactAndOneRetainedDraft() throws Exception {
        var f=fixture();var ready=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<JdbcEffectLedger.Prepared>> futures=new ArrayList<>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{ready.await();return prepare(f);}));
            ready.countDown();var first=futures.getFirst().get(15,TimeUnit.SECONDS);
            for(var result:futures)assertEquals(first,result.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,count(f,"event"));assertEquals(1,history(f).size());
    }
    @Test void ambientCallerRollbackCannotEraseFactsAndClaimCallbackRunsOutsideIt() throws Exception {
        var f=fixture();caller.begin();
        try {
            ledger.exclusively(f.command().key(),()->{
                try{assertEquals(Status.STATUS_NO_TRANSACTION,manager.getStatus());}catch(SystemException failed){throw new IllegalStateException(failed);}
                return prepare(f);
            });
            assertEquals(Status.STATUS_ACTIVE,caller.getStatus());
        } finally {caller.rollback();}
        assertEquals(1,count(f,"ledger"));assertEquals(1,history(f).size());
    }
    @Test void directMutationAndTruncateRefuseAndPrivilegedOwnFixtureCorruptionIsDetectedOnRead() throws Exception {
        var f=fixture();prepare(f);long journal=history(f).getFirst().source().sequence();
        for(var table:List.of("audit_source","audit_scope"))try(var c=owned.getConnection()) {
            c.setAutoCommit(false);
            try {assertEquals("42501",assertThrows(SQLException.class,()->sql(c,"update effect."+table+" set scope_fingerprint=scope_fingerprint")).getSQLState());}
            finally{c.rollback();}
        }
        try(var c=owned.getConnection()) {
            c.setAutoCommit(false);
            try{assertEquals("42501",assertThrows(SQLException.class,()->sql(c,"truncate effect.audit_scope,effect.audit_source,effect.audit_delivery")).getSQLState());}
            finally{c.rollback();}
        }
        try(var c=owned.getConnection()) {
            c.setAutoCommit(false);
            try {
                // This schema exists only in our freshly CREATED database. Rollback restores rows AND trigger state.
                sql(c,"alter table effect.audit_source disable trigger audit_source_no_change");
                try(var q=c.prepareStatement("update effect.audit_source set content_fingerprint=repeat('f',64) where source_event_id=?")){q.setLong(1,journal);q.executeUpdate();}
                assertThrows(IllegalStateException.class,()->producer.find(c,journal));
            } finally{c.rollback();}
        }
        assertEquals(1,history(f).size());
    }

    EffectAuditDelivery delivery(DataSource ds,IdempotentAuditSink sink){return new EffectAuditDelivery(ds,manager,producer,sink);}
    EffectAuditProducer.Retained initial(Fixture f){return history(f).getFirst();}
    String ack(long journal) throws SQLException {
        try(var c=owned.getConnection();var q=c.prepareStatement("select ack_source_key from effect.audit_delivery where source_event_id=?")) {
            q.setLong(1,journal);try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    /** EXPLICIT CENTRAL DATA FIXTURE: existing append commits BEFORE binding insert. Not an appendOnce implementation
     * or proof of atomic central commit/idempotence. These cases exercise the REAL delivery queue/JDBC protocol only. */
    AuditReceipt seedCentralReceipt(EffectAuditProducer.Retained value) throws SQLException {
        var event=new AuditTrail(owned).append(value.draft());var s=value.source();
        var receipt=new AuditReceipt(s,event.id(),AuditDraftFingerprint.chainOf(event),event.sequence(),event.hash(),AuditDraftFingerprint.of(event));
        receipt.requireMatches(s,value.draft());
        try(var c=owned.getConnection();var q=c.prepareStatement("""
            insert into audit.source_binding(source_key,schema_version,scope_fingerprint,key_fingerprint,transition,
              source_sequence,content_fingerprint,event_id,chain_id,chain_sequence,hash) values(?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            q.setString(1,s.key());q.setInt(2,s.schemaVersion());q.setString(3,s.scopeFingerprint());q.setString(4,s.keyFingerprint());
            q.setString(5,s.transition().name());q.setLong(6,s.sequence());q.setString(7,s.contentFingerprint());q.setString(8,event.id().value());
            q.setString(9,receipt.chainId());q.setLong(10,event.sequence());q.setString(11,event.hash());q.executeUpdate();
        }
        return receipt;
    }
    @Test void readyQueueAndDirectDeliveryPreservePerKeyPredecessorsAndRejectInvalidBounds() throws Exception {
        var f=fixture();prepare(f);ledger.markAttempted(f.command().key());var facts=history(f);var calls=new AtomicInteger();
        var relay=delivery(owned,(s,d)->{calls.incrementAndGet();throw new IllegalStateException("not implemented test consumer");});
        long first=facts.get(0).source().sequence(),second=facts.get(1).source().sequence();
        assertTrue(relay.ready(128).contains(first));assertFalse(relay.ready(128).contains(second));
        assertThrows(IllegalStateException.class,()->relay.deliver(second));assertEquals(0,calls.get());
        assertThrows(IllegalArgumentException.class,()->relay.ready(0));assertThrows(IllegalArgumentException.class,()->relay.drain(129));
        assertThrows(IllegalArgumentException.class,()->relay.deliver(0));assertEquals(0,calls.get());
        var receipt=seedCentralReceipt(facts.getFirst());var secondReceipt=seedCentralReceipt(facts.get(1));
        try(var c=owned.getConnection()) {
            c.setAutoCommit(false);
            try{assertEquals("23514",assertThrows(SQLException.class,()->sql(c,"update effect.audit_delivery set ack_source_key='"+secondReceipt.source().key()+"' where source_event_id="+second)).getSQLState());}
            finally{c.rollback();}
        }
        delivery(owned,(s,d)->receipt).deliver(first);
        assertTrue(relay.ready(128).contains(second));assertFalse(relay.ready(128).contains(first));
    }
    @Test void acknowledgmentRequiresExactStoredBindingAndCannotBeResetOrErased() throws Exception {
        var f=fixture();prepare(f);var value=initial(f);long id=value.source().sequence();
        var invented=new AuditReceipt(value.source(),value.draft().id(),AuditDraftFingerprint.chainOf(value.draft()),1,"e".repeat(64),value.source().contentFingerprint());
        assertThrows(IllegalStateException.class,()->delivery(owned,(s,d)->invented).deliver(id));assertNull(ack(id));
        var receipt=seedCentralReceipt(value);
        var wrong=new AuditReceipt(receipt.source(),receipt.eventId(),receipt.chainId(),receipt.chainSequence(),"f".repeat(64),receipt.contentFingerprint());
        assertNotEquals(receipt.hash(),wrong.hash());assertThrows(IllegalStateException.class,()->delivery(owned,(s,d)->wrong).deliver(id));assertNull(ack(id));
        var calls=new AtomicInteger();var relay=delivery(owned,(s,d)->{calls.incrementAndGet();assertEquals(value.source(),s);assertEquals(value.draft(),d);return receipt;});
        assertEquals(receipt,relay.deliver(id));assertEquals(receipt,relay.deliver(id));assertEquals(2,calls.get());assertEquals(receipt.source().key(),ack(id));
        for(String statement:List.of("update effect.audit_delivery set ack_source_key=null,acked_at=null where source_event_id="+id,
                "delete from effect.audit_delivery where source_event_id="+id,"truncate effect.audit_delivery"))try(var c=owned.getConnection()) {
            c.setAutoCommit(false);try{assertEquals("42501",assertThrows(SQLException.class,()->sql(c,statement)).getSQLState());}finally{c.rollback();}
        }
    }
    @Test void centralFailureAfterSeededCommitLeavesSameSourcePendingForExplicitReadback() throws Exception {
        var f=fixture();prepare(f);var value=initial(f);long id=value.source().sequence();var saved=new AtomicReference<AuditReceipt>();var calls=new AtomicInteger();
        var relay=delivery(owned,(s,d)->{
            assertEquals(value.source(),s);assertEquals(value.draft(),d);
            if(calls.getAndIncrement()==0) {
                try{saved.set(seedCentralReceipt(value));}catch(SQLException failed){throw new IllegalStateException(failed);}
                throw new IllegalStateException("injected uncertain consumer reply AFTER seeded central facts");
            }
            return saved.get(); // explicit DATA-fixture readback, not a real appendOnce consumer.
        });
        assertThrows(IllegalStateException.class,()->relay.deliver(id));assertNull(ack(id));assertTrue(relay.ready(128).contains(id));
        assertEquals(saved.get(),relay.deliver(id));assertEquals(value.source().key(),ack(id));assertEquals(2,calls.get());assertEquals(value,initial(f));
    }
    @Test void actualLocalAcknowledgmentCommitReplyLossReopensSameReceiptWithoutReset() throws Exception {
        var f=fixture();prepare(f);var value=initial(f);var receipt=seedCentralReceipt(value);long id=value.source().sequence();var armed=new AtomicBoolean(false);
        var relay=delivery(loseCommitReply(owned,armed),(s,d)->{armed.set(true);return receipt;});
        assertThrows(StoreUnavailableException.class,()->relay.deliver(id));assertFalse(armed.get());assertEquals(receipt.source().key(),ack(id));
        var reopened=delivery(owned,(s,d)->receipt);assertEquals(receipt,reopened.deliver(id));assertFalse(reopened.ready(128).contains(id));
    }
    static DataSource oneSlot(DataSource delegate,AtomicInteger active) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value;try{value=m.invoke(delegate,a);}catch(InvocationTargetException failed){throw failed.getCause();}
            if(!m.getName().equals("getConnection"))return value;
            var c=(Connection)value;if(active.incrementAndGet()!=1){active.decrementAndGet();c.close();throw new SQLException("test one-slot source pool exhausted");}
            var closed=new AtomicBoolean();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(q,cm,ca)->{
                try{return cm.invoke(c,ca);}catch(InvocationTargetException failed){throw failed.getCause();}
                finally{if(cm.getName().equals("close") && closed.compareAndSet(false,true))active.decrementAndGet();}
            });
        });
    }
    @Test void deliveryClosesSourceConnectionsBeforeConsumerAndSurvivesAmbientCallerRollback() throws Exception {
        var f=fixture();prepare(f);var value=initial(f);var receipt=seedCentralReceipt(value);var active=new AtomicInteger();
        var relay=delivery(oneSlot(owned,active),(s,d)->{
            assertEquals(0,active.get());try{assertEquals(Status.STATUS_NO_TRANSACTION,manager.getStatus());}catch(SystemException failed){throw new IllegalStateException(failed);}
            return receipt;
        });
        caller.begin();try{assertEquals(receipt,relay.deliver(value.source().sequence()));assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertEquals(0,active.get());assertEquals(value.source().key(),ack(value.source().sequence()));
    }
    @Test void delayedLowerIdCommitRemainsReadyAfterHigherIdWasAlreadyAcknowledged() throws Exception {
        var delayed=fixture();var later=fixture();var atCommit=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        var ds=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object result;try{result=m.invoke(owned,a);}catch(InvocationTargetException failed){throw failed.getCause();}
            if(!m.getName().equals("getConnection"))return result;var c=(Connection)result;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(q,cm,ca)->{
                if(cm.getName().equals("commit") && first.compareAndSet(true,false)){atCommit.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new SQLException("test commit release timed out");}
                try{return cm.invoke(c,ca);}catch(InvocationTargetException failed){throw failed.getCause();}
            });
        });
        var pool=Executors.newSingleThreadExecutor();Future<?> pending=null;
        try {
            pending=pool.submit(()->new JdbcEffectLedger(ds,manager,producer).prepareScoped(delayed.scope(),delayed.command(),Duration.ZERO,now()));
            assertTrue(atCommit.await(10,TimeUnit.SECONDS));prepare(later);var high=initial(later);long highId=high.source().sequence();
            var receipt=seedCentralReceipt(high);delivery(owned,(s,d)->receipt).deliver(highId);
            release.countDown();pending.get(10,TimeUnit.SECONDS);
            long lowId=initial(delayed).source().sequence();assertTrue(lowId<highId);
            var relay=delivery(owned,(s,d)->{throw new IllegalStateException("no consumer called by ready");});
            assertTrue(relay.ready(128).contains(lowId));assertFalse(relay.ready(128).contains(highId));
        } finally {release.countDown();if(pending!=null)pending.cancel(true);pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void concurrentDeliveriesRetainOneAcknowledgmentForTheExactSeededCentralReceipt() throws Exception {
        var f=fixture();prepare(f);var value=initial(f);var receipt=seedCentralReceipt(value);long id=value.source().sequence();
        var relay=delivery(owned,(s,d)->receipt);var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<AuditReceipt>> results=new ArrayList<>();for(int i=0;i<4;i++)results.add(pool.submit(()->relay.deliver(id)));
            for(var result:results)assertEquals(receipt,result.get(15,TimeUnit.SECONDS));
        } finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(receipt.source().key(),ack(id));assertEquals(1,count(f,"audit_delivery"));
    }
}
