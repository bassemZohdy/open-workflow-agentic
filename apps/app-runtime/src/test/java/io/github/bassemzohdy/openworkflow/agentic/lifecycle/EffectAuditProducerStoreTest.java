package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
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
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.postgresql.ds.PGSimpleDataSource;

/** Real journal/source transactions in a database created ONLY by this fixture. No target or central append runs. */
@QuarkusTest @TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class EffectAuditProducerStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    @Inject JdbcEffectLedger defaultLedger;
    PGSimpleDataSource admin,owned;
    String database;
    boolean created;
    JdbcInstanceStore registry;
    EffectAuditProducer producer;
    JdbcEffectLedger ledger;
    static final Subject OWNER=new Subject(SubjectKind.USER,"alíce-𝄞");
    record Fixture(EffectScope scope,EffectCommand command) { }
    static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    static PGSimpleDataSource dataSource(String name) {
        var ds=new PGSimpleDataSource();ds.setUrl(System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/agentic"));
        if(name!=null)ds.setDatabaseName(name);
        ds.setUser(System.getenv().getOrDefault("DB_USER","postgres"));ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD","postgres"));
        ds.setConnectTimeout(5);ds.setSocketTimeout(10);return ds;
    }
    @BeforeAll void createOwnedDatabase() throws Exception {
        admin=dataSource(null);database="gpt_eap_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(database.matches("gpt_eap_[0-9a-f]{32}"));
        try(var c=admin.getConnection()){sql(c,"create database "+database);created=true;}
        owned=dataSource(database);
        try(var c=owned.getConnection();var q=c.createStatement();var r=q.executeQuery("select current_database()")){r.next();assertEquals(database,r.getString(1));}
        // Initialize BEFORE ANY owned schema/table writes; never baseline or clean a pre-existing database.
        Flyway.configure().dataSource(owned).locations("classpath:db/migration").load().migrate();
        registry=new JdbcInstanceStore(owned);producer=new EffectAuditProducer();ledger=new JdbcEffectLedger(owned,manager,producer);
    }
    @AfterAll void dropOnlyOwnedDatabase() throws Exception {
        if(!created)return;
        assertNotNull(admin);assertTrue(database.matches("gpt_eap_[0-9a-f]{32}"));
        // created was set ONLY after CREATE succeeded. No IF EXISTS/force/termination of another session.
        try(var c=admin.getConnection()){sql(c,"drop database "+database);created=false;}
    }
    static void sql(Connection c,String text) throws SQLException {try(var q=c.createStatement()){q.setQueryTimeout(15);q.execute(text);}}
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
        assertEquals(1,count(f,"ledger"));assertEquals(1,count(f,"event"));assertEquals(1,count(f,"audit_scope"));assertEquals(1,count(f,"audit_source"));
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
    @Test void aSourceInsertFailureRollsBackIntentScopeJournalAndDraft() throws Exception {
        var f=fixture();String name="gpt_source_fault_"+UUID.randomUUID().toString().replace("-","");assertTrue(name.matches("gpt_source_fault_[0-9a-f]{32}"));
        try(var c=owned.getConnection()) {
            sql(c,"create function effect."+name+"() returns trigger language plpgsql as $$ begin if new.attributes->>'oa.instance'='"+f.scope().instanceId().value()+"' then raise exception 'injected source failure'; end if; return new; end $$");
            sql(c,"create trigger "+name+" before insert on effect.audit_source for each row execute function effect."+name+"()");
        }
        try {
            assertThrows(StoreUnavailableException.class,()->prepare(f));
            for(var table:List.of("ledger","event","audit_scope","audit_source"))assertEquals(0,count(f,table));
        } finally {
            try(var c=owned.getConnection()){sql(c,"drop trigger "+name+" on effect.audit_source");sql(c,"drop function effect."+name+"()");}
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
            try{assertEquals("42501",assertThrows(SQLException.class,()->sql(c,"truncate effect.audit_scope,effect.audit_source cascade")).getSQLState());}
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
    @Test void theDefaultCdiLedgerDoesNotEnableScopedProduction() throws Exception {
        var f=fixture();var error=assertThrows(IllegalStateException.class,()->defaultLedger.prepareScoped(f.scope(),f.command(),Duration.ZERO,now()));
        assertTrue(error.getMessage().contains("producer is not configured"));assertEquals(0,count(f,"ledger"));
    }
    @Test void failureOfTheActualPendingQueueRollsBackAllFiveProducerFacts() throws Exception {
        var f=fixture();
        try(var c=owned.getConnection()) {
            sql(c,"create function effect.fail_delivery_probe() returns trigger language plpgsql as $$ begin raise exception 'owned queue fault' using errcode='40001'; end $$;create trigger fail_delivery_probe before insert on effect.audit_delivery for each row execute function effect.fail_delivery_probe()");
        }
        try {
            var error=assertThrows(StoreUnavailableException.class,()->prepare(f));assertEquals("40001",((SQLException)error.getCause()).getSQLState());
            for(String table:List.of("ledger","event","audit_scope","audit_source","audit_delivery"))assertEquals(0,count(f,table));
        }finally{try(var c=owned.getConnection()){sql(c,"drop trigger fail_delivery_probe on effect.audit_delivery;drop function effect.fail_delivery_probe()");}}
    }
    @Test void retentionProbesCannotPassOnlyBecauseADescendantGuardFires() throws Exception {
        for(String parent:List.of("audit_scope","audit_source")) {
            var f=fixture();prepare(f);
            try(var c=owned.getConnection()) {
                c.setAutoCommit(false);
                try {
                    var children=parent.equals("audit_scope")?List.of("audit_source","audit_delivery"):List.of("audit_delivery");
                    for(String child:children)sql(c,"alter table effect."+child+" disable trigger "+child+"_no_truncate");
                    var save=c.setSavepoint();var error=assertThrows(SQLException.class,()->sql(c,"truncate effect."+parent+" cascade"));assertEquals("42501",error.getSQLState());
                    c.rollback(save);sql(c,"alter table effect."+parent+" disable trigger "+parent+"_no_truncate");
                    assertDoesNotThrow(()->sql(c,"truncate effect."+parent+" cascade"),"without only the intended guard the same probe must stop refusing");
                }finally{c.rollback();}
            }
            assertEquals(1,history(f).size());assertEquals(1,count(f,"audit_delivery"));
        }
    }
    @Test void anOrphanedJournalRefusesInsteadOfBeingReportedAbsent() throws Exception {
        var f=fixture();prepare(f);long journal=history(f).getFirst().source().sequence();
        try(var c=owned.getConnection()) {
            c.setAutoCommit(false);
            try {
                assertTrue(producer.find(c,Long.MAX_VALUE).isEmpty(),"only an actually absent source is empty");
                // Only this owned private database; rollback restores both the actual row and every trigger.
                sql(c,"alter table effect.event disable trigger all");
                try(var q=c.prepareStatement("delete from effect.event where id=?")){q.setLong(1,journal);assertEquals(1,q.executeUpdate());}
                assertThrows(IllegalStateException.class,()->producer.find(c,journal));
                assertThrows(IllegalStateException.class,()->producer.forKey(c,f.command().key(),0,128));
            }finally{c.rollback();}
        }
        assertEquals(1,history(f).size());
    }

    TransactionManager loseResumeReply() {
        return (TransactionManager)Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),new Class[]{TransactionManager.class},(proxy,method,args)->{
            Object result;
            try{result=method.invoke(manager,args);}catch(InvocationTargetException failed){throw failed.getCause();}
            if(method.getName().equals("resume"))throw new SystemException("owned resume reply lost after actual resume");
            return result;
        });
    }
    @Test void resumeReplyFailureDoesNotHideTheOriginalSourceRefusal() throws Exception {
        var f=fixture();var wrong=new EffectScope(f.scope().tenant(),f.scope().instanceId(),new Subject(SubjectKind.SERVICE,OWNER.id()));
        var faulty=new JdbcEffectLedger(owned,loseResumeReply(),producer);
        caller.begin();
        try {
            var error=assertThrows(IllegalStateException.class,()->faulty.prepareScoped(wrong,f.command(),Duration.ZERO,now()));
            assertTrue(error.getMessage().contains("provenance or content differs"));assertEquals(1,error.getSuppressed().length);
            assertTrue(error.getSuppressed()[0].getMessage().contains("resume reply lost"));assertEquals(Status.STATUS_ACTIVE,caller.getStatus());
        }finally{caller.rollback();}
        assertEquals(0,count(f,"ledger"));
    }
    @Test void actualCommitThenResumeReplyLossRetainsTheSameSourceWithoutAnAcknowledgedReturn() throws Exception {
        var f=fixture();var faulty=new JdbcEffectLedger(owned,loseResumeReply(),producer);
        caller.begin();
        try {
            var error=assertThrows(StoreUnavailableException.class,()->faulty.prepareScoped(f.scope(),f.command(),Duration.ZERO,now()));
            assertTrue(error.getMessage().contains("cannot be resumed"));assertEquals(Status.STATUS_ACTIVE,caller.getStatus());
        }finally{caller.rollback();}
        var before=history(f);assertEquals(1,before.size());prepare(f);assertEquals(before,history(f));assertEquals(1,count(f,"audit_delivery"));
    }

}
