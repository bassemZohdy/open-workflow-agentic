package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchOutputFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.postgresql.ds.PGSimpleDataSource;

/** Actual ordered V76 in private databases. Data constraints are not a fresh callback or current authority. */
class ResourceExecutionMigrationTest {
    private static final Subject USER=new Subject(SubjectKind.USER,"alice-δοκιμή");
    private static final Subject WORKLOAD=new Subject(SubjectKind.WORKFLOW,"execution-schema-fixture");
    private static final String PROFILE=ResearchOutputFingerprint.PROFILE,MEDIA=ResearchOutputFingerprint.CONTENT_TYPE;
    private static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    record Fixture(InstanceRecord row,ResourceCallBinding binding) {
        ResourceExecutionEntry entry(long epoch){return new ResourceExecutionEntry(binding.scope(),epoch);}
    }

    @Test void freshOrderedMigrationInstallsEmptyRetainedFactsAndRestoresTheConfiguredNamespace() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);db.flyway(null).validate();
            assertEquals(1,count(db.owner,"select count(*) from public.flyway_schema_history where version='76' and success"));
            assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
            assertEquals(0,count(db.owner,"select count(*) from task.resource_execution_outcome"));
            assertEquals("public",text(db.owner,"select pg_catalog.current_schema()::text"));
            assertEquals("pg_catalog",text(db.owner,"select array_to_string(proconfig,',') from pg_proc where oid='task.resource_execution_insert_guard()'::regprocedure").replace("search_path=", ""));
        }
    }

    @Test void nonemptySeventyFivePointOneUpgradePreservesAttributionWithoutInventingEntries() throws Exception {
        try(var db=new Database()) {
            db.migrate("75.1");var f=fixture(db,"a".repeat(64));var before=attribution(db.owner);
            db.migrate(null);db.flyway(null).validate();assertEquals(before,attribution(db.owner));
            assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
            assertEquals(f.binding(),new JdbcResourceCallBindings(db.owner).find(f.binding().issuer(),f.binding().tokenId()).orElseThrow());
            insert(db.owner,f,1);assertEquals(1,count(db.owner,"select count(*) from task.resource_execution"));
        }
    }

    @Test void conflictingPreexistingFactTableRollsBackWithoutRepairOrPartialFunctions() throws Exception {
        try(var db=new Database()) {
            db.migrate("75.1");fixture(db,"a".repeat(64));var before=attribution(db.owner);
            execute(db.owner,"create table task.resource_execution(marker text); insert into task.resource_execution values('owned-do-not-reset')");
            assertThrows(FlywayException.class,()->db.migrate(null));
            assertEquals("owned-do-not-reset",text(db.owner,"select marker from task.resource_execution"));
            assertEquals(before,attribution(db.owner));
            assertEquals(0,count(db.owner,"select count(*) from public.flyway_schema_history where version='76'"));
            assertEquals(0,count(db.owner,"select count(*) from pg_proc where oid=to_regprocedure('task.resource_execution_identity(text,text,bigint)')"));
            assertEquals(0,count(db.owner,"select count(*) from pg_class where oid=to_regclass('task.resource_execution_outcome')"));
        }
    }

    @Test void databaseCanonicalFingerprintsMatchTheSharedJdkContractIncludingUnicode() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            try(var c=db.owner.getConnection();var q=c.prepareStatement("select task.resource_call_data_contract(c),task.resource_execution_identity(c.call_key,c.fingerprint,?) from task.resource_call c where call_key=?")) {
                for(long epoch:List.of(1L,2L,Long.MAX_VALUE)) {
                    q.setLong(1,epoch);q.setString(2,f.binding().scope().key());
                    try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(f.binding().scope().fingerprint(),r.getString(1));assertEquals(f.entry(epoch).fingerprint(),r.getString(2));assertFalse(r.next());}
                }
            }
        }
    }

    @TestFactory Stream<DynamicTest> changedRegistryAttributionCannotCreateAnEntry() {
        var changes=new LinkedHashMap<String,String>();
        changes.put("owner-kind","owner_kind='AGENT'");changes.put("owner-id","owner_id='someone-else'");
        changes.put("engine","engine_instance_id='different-engine'");changes.put("definition","definition_id='other/definition'");
        changes.put("version","definition_version='999'");changes.put("cancel-requested","status='CANCEL_REQUESTED'");
        changes.put("expired-wait","status='WAITING',wait_deadline=clock_timestamp()-interval '1 second'");
        return changes.entrySet().stream().map(change->DynamicTest.dynamicTest(change.getKey(),()->{
            try(var db=new Database()) {
                db.migrate(null);var f=fixture(db,"a".repeat(64));
                execute(db.owner,"update task.instance set "+change.getValue()+" where id='"+f.row().id()+"'");
                refused("23514",()->insert(db.owner,f,1));assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
            }
        }));
    }

    @Test void expiredOrFutureBindingAndChangedContractEpochOrBindingAreRefusedIndependentlyOfJavaValidation() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            var sample=databaseNow(db.owner);var expired=credential(db,f,sample.minusSeconds(240),sample.minusSeconds(120));
            var future=credential(db,f,sample.plusSeconds(60),sample.plusSeconds(180));
            for(var binding:List.of(expired,future))refused("23514",()->raw(db.owner,f,1,f.entry(1).fingerprint(),f.binding().scope().fingerprint(),binding.key()));
            refused("23514",()->raw(db.owner,f,1,"re1-"+"0".repeat(64),f.binding().scope().fingerprint(),f.binding().key()));
            refused("23514",()->raw(db.owner,f,1,f.entry(1).fingerprint(),"0".repeat(64),f.binding().key()));
            String zero="re1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.execution-entry.v1").text(f.binding().scope().key()).text(f.binding().scope().fingerprint()).number(0).toBytes());
            refused("23514",()->raw(db.owner,f,0,zero,f.binding().scope().fingerprint(),f.binding().key()));
            var other=fixture(db,"a".repeat(64));
            refused("23514",()->raw(db.owner,f,1,f.entry(1).fingerprint(),f.binding().scope().fingerprint(),other.binding().key()));
            refused("23503",()->raw(db.owner,f,1,f.entry(1).fingerprint(),f.binding().scope().fingerprint(),"rb1-"+"0".repeat(64)));
            assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
        }
    }

    @Test void canonicalOccurrenceAnchorIsRequiredEvenIfCallMetadataAndCredentialStillExist() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            // Corrupt only this owned database to distinguish the V74 anchor FK from the older call-row FK.
            execute(db.owner,"alter table task.resource_occurrence disable trigger user; delete from task.resource_occurrence; alter table task.resource_occurrence enable trigger user");
            refused("23503",()->insert(db.owner,f,1));
            assertEquals(0,count(db.owner,"select count(*) from task.resource_occurrence"));
            assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
        }
    }

    @Test void oneFactPerOriginalCallSurvivesConcurrentEpochCredentialAndExecutorChanges() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
            try {
                var futures=new ArrayList<Future<Boolean>>();
                for(int i=0;i<8;i++)futures.add(pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{insert(db.owner,f,1);return true;}catch(SQLException conflict){assertEquals("23505",conflict.getSQLState());return false;}}));
                start.countDown();int winners=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))winners++;
                assertEquals(1,winners);assertEquals(1,count(db.owner,"select count(*) from task.resource_execution"));
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
            var sample=databaseNow(db.owner);var renewed=credential(db,f,sample.minusSeconds(1),sample.plusSeconds(120));
            refused("23505",()->raw(db.owner,f,2,f.entry(2).fingerprint(),f.binding().scope().fingerprint(),renewed.key()));
            assertEquals(f.entry(1).fingerprint(),text(db.owner,"select entry_key from task.resource_execution"));
            // An INSERT count is data uniqueness, not a freshly acknowledged callback or current authority.
        }
    }

    @Test void newForeignKeysDoNotMaskEarlierParentTruncateGuards() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);
            String[][] parents={
                {"identity.issued_token_binding","issued_token_binding_no_truncate",
                    "issued_token_binding is append-only: TRUNCATE is refused"},
                {"task.resource_occurrence","resource_occurrence_no_truncate",
                    "resource occurrence is immutable and retained"}
            };
            for(var parent:parents) {
                try(var c=db.owner.getConnection()) {
                    c.setAutoCommit(false);
                    try(var st=c.createStatement()) {
                        var refused=assertThrows(SQLException.class,()->st.execute("truncate "+parent[0]+" cascade"));
                        assertEquals("42501",refused.getSQLState());
                        assertTrue(refused.getMessage().contains(parent[2]),refused.getMessage());
                    } finally {c.rollback();}
                }
                // Deliberately remove only the intended parent guard in this private database. A child can still
                // refuse, but its 42501 must never satisfy the strengthened parent-specific probes above.
                try(var c=db.owner.getConnection()) {
                    c.setAutoCommit(false);
                    try(var st=c.createStatement()) {
                        st.execute("alter table "+parent[0]+" disable trigger "+parent[1]);
                        var refused=assertThrows(SQLException.class,()->st.execute("truncate "+parent[0]+" cascade"));
                        assertEquals("42501",refused.getSQLState());
                        assertFalse(refused.getMessage().contains(parent[2]),refused.getMessage());
                    } finally {c.rollback();}
                }
            }
        }
    }

    @Test void entryAndOutcomeFactsRefuseOwnerMutationAndStatementTruncation() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));insert(db.owner,f,1);
            outcome(db.owner,f,UUID.randomUUID(),UUID.randomUUID(),f.binding().scope().targetFingerprint(),PROFILE,MEDIA,"c".repeat(64));
            for(String table:List.of("resource_execution","resource_execution_outcome")) {
                immutable(db.owner,table,"UPDATE","update task."+table+" set entry_key=entry_key");
                immutable(db.owner,table,"DELETE","delete from task."+table);
                // Include the referencing child; an FK error must not mask the parent's truncate guard.
                immutable(db.owner,table,"TRUNCATE","truncate task."+table+" cascade");
                assertEquals(1,count(db.owner,"select count(*) from task."+table));
            }
        }
    }

    @Test void outcomeReferencesAreExactTargetScopedImmutableDataAndMayOutliveCancellation() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var a=fixture(db,"a".repeat(64));var b=fixture(db,"a".repeat(64));var c=fixture(db,"b".repeat(64));
            for(var f:List.of(a,b,c))insert(db.owner,f,1);
            UUID run=UUID.randomUUID(),checkpoint=UUID.randomUUID();
            execute(db.owner,"update task.instance set status='CANCEL_REQUESTED' where id='"+a.row().id()+"'");
            outcome(db.owner,a,run,checkpoint,a.binding().scope().targetFingerprint(),PROFILE,MEDIA,"c".repeat(64));
            refused("23505",()->outcome(db.owner,a,UUID.randomUUID(),UUID.randomUUID(),a.binding().scope().targetFingerprint(),PROFILE,MEDIA,"c".repeat(64)));
            refused("23505",()->outcome(db.owner,b,run,UUID.randomUUID(),b.binding().scope().targetFingerprint(),PROFILE,MEDIA,"c".repeat(64)));
            refused("23505",()->outcome(db.owner,b,UUID.randomUUID(),checkpoint,b.binding().scope().targetFingerprint(),PROFILE,MEDIA,"c".repeat(64)));
            refused("23514",()->outcome(db.owner,b,UUID.randomUUID(),UUID.randomUUID(),"f".repeat(64),PROFILE,MEDIA,"c".repeat(64)));
            refused("23514",()->outcome(db.owner,b,UUID.randomUUID(),UUID.randomUUID(),b.binding().scope().targetFingerprint(),"unknown-profile",MEDIA,"c".repeat(64)));
            refused("23514",()->outcome(db.owner,b,UUID.randomUUID(),UUID.randomUUID(),b.binding().scope().targetFingerprint(),PROFILE,"application/json","c".repeat(64)));
            refused("23514",()->outcome(db.owner,b,UUID.randomUUID(),UUID.randomUUID(),b.binding().scope().targetFingerprint(),PROFILE,MEDIA,"raw-result-must-not-fit"));
            outcome(db.owner,c,run,checkpoint,c.binding().scope().targetFingerprint(),PROFILE,MEDIA,"d".repeat(64));
            assertEquals(2,count(db.owner,"select count(*) from task.resource_execution_outcome"));
            assertEquals(2,count(db.owner,"select count(*) from task.resource_execution_outcome where observed_at > timestamptz '2026-01-01'"));
            // Synthetic references do not prove a graph ran/completed or that private output may be released.
        }
    }

    @Test void databaseClockAfterARegistryLockWaitRefusesTheNowExpiredBinding() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var sample=databaseNow(db.owner);var expiring=credential(db,f,sample.minusSeconds(1),sample.plusSeconds(2));
            var pid=new AtomicInteger();var pool=Executors.newSingleThreadExecutor();
            try(var holder=db.owner.getConnection()) {
                holder.setAutoCommit(false);try(var q=holder.prepareStatement("select id from task.instance where id=? for update")){q.setString(1,f.row().id().value());q.executeQuery().close();}
                Future<String> attempt=pool.submit(()->{
                    try(var c=db.owner.getConnection()){pid.set((int)scalar(c,"select pg_backend_pid()"));try{raw(c,f,1,f.entry(1).fingerprint(),f.binding().scope().fingerprint(),expiring.key());return "inserted";}catch(SQLException denied){return denied.getSQLState();}}
                });
                await(()->pid.get()!=0 && count(db.owner,"select count(*) from pg_locks where pid="+pid.get()+" and not granted")>0);
                await(()->count(db.owner,"select count(*) from task.resource_call_binding where binding_key='"+expiring.key()+"' and expires_at <= clock_timestamp()")>0);
                holder.rollback();assertEquals("23514",attempt.get(10,TimeUnit.SECONDS));
            } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
            assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
        }
    }

    @Test void waitDeadlineIsRecheckedAfterExpensiveCanonicalValidation() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            // Delay only this private database's validator to model time passing during validation work.
            execute(db.owner,"alter function task.resource_call_data_contract(task.resource_call) rename to resource_call_data_contract_original");
            execute(db.owner,"create function task.resource_call_data_contract(invocation task.resource_call) returns text language plpgsql set search_path=pg_catalog as $$ begin perform pg_sleep(0.8); return task.resource_call_data_contract_original(invocation); end $$");
            execute(db.owner,"update task.instance set status='WAITING',wait_deadline=clock_timestamp()+interval '0.5 second' where id='"+f.row().id()+"'");
            refused("23514",()->insert(db.owner,f,1));assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
        }
    }

    @Test void callerSearchPathCannotReplaceTheDatabaseClockForExpiredEntryMetadata() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var sample=databaseNow(db.owner);var expired=credential(db,f,sample.minusSeconds(240),sample.minusSeconds(120));
            execute(db.owner,"create schema shadow; create function shadow.clock_timestamp() returns timestamptz language sql as 'select pg_catalog.clock_timestamp()-interval ''180 seconds'''");
            var shadow=db.data();shadow.setOptions("-c search_path=shadow,public,pg_catalog");
            refused("23514",()->raw(shadow,f,1,f.entry(1).fingerprint(),f.binding().scope().fingerprint(),expired.key()));
            assertEquals(0,count(db.owner,"select count(*) from task.resource_execution"));
        }
    }

    static Fixture fixture(Database db,String target) throws SQLException {
        var registry=new JdbcInstanceStore(db.owner);var ids=UlidGenerator.shared();String tenant="execution-"+UUID.randomUUID();
        var request=new StartRequest(tenant,USER,new DefinitionRef("agentic/execution-schema-test","1"),Map.of(),"start-"+UUID.randomUUID());
        var row=registry.insertIfAbsent(InstanceRecord.starting(ids.next(),request,now()),"test.start").record();
        row=registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",USER);
        var scope=new ResourceCallScope(tenant,row.id(),USER,"app-𝄞",row.engineInstanceId(),"agentic","execution-schema-test","1","/do/0/研究",1,"root/foreach/é","agent-run","run",target,"b".repeat(64));
        var clock=Clock.systemUTC();
        // Fixture-only audit sink: this proves data DDL, not verified identity or mandatory dispatch audit.
        var grants=new DelegationGrants(new DelegationGrantStore(db.owner),new IdentityAudit(event->{},clock,ids),clock,ids,Duration.ofHours(2));
        var grant=grants.grant(tenant,USER,USER,WORKLOAD,Set.of("oa.agent.run"),now().plusSeconds(3600),null);
        var issued=databaseNow(db.owner).minusSeconds(1);var expires=issued.plusSeconds(120);String issuer="https://execution-idp.test",jti=UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(db.owner).bind(issuer,jti,new IssuedTokenBindings.Binding(grant.id(),tenant,expires));
        var binding=new ResourceCallBinding(issuer,jti,grant.id(),scope,issued,expires);
        new JdbcResourceCallBindings(db.owner).bind(binding);return new Fixture(row,binding);
    }
    static ResourceCallBinding credential(Database db,Fixture f,Instant issued,Instant expires) {
        var old=f.binding();String jti=UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(db.owner).bind(old.issuer(),jti,new IssuedTokenBindings.Binding(old.issuingGrantId(),old.scope().tenant(),expires));
        return new JdbcResourceCallBindings(db.owner).bind(new ResourceCallBinding(old.issuer(),jti,old.issuingGrantId(),old.scope(),issued,expires));
    }
    private static int insert(DataSource data,Fixture f,long epoch) throws SQLException {
        try {return raw(data,f,epoch,f.entry(epoch).fingerprint(),f.binding().scope().fingerprint(),f.binding().key());}
        catch(SQLException failure) {
            try {failure.addSuppressed(new SQLException("owned fixture metadata: "+text(data,
                "select concat('clock=',clock_timestamp(),'; issued=',b.issued_at,'; expires=',b.expires_at,'; deadline=',r.wait_deadline,'; state=',r.status,'; call matches=',b.call_key=c.call_key) from task.resource_call_binding b join task.resource_call c on c.call_key=b.call_key join task.instance r on r.id=c.instance_id where b.binding_key='"+f.binding().key()+"'")));}
            catch(Exception diagnostic){failure.addSuppressed(diagnostic);}
            throw failure;
        }
    }
    private static int raw(DataSource data,Fixture f,long epoch,String key,String contract,String binding) throws SQLException {
        try(var c=data.getConnection()){return raw(c,f,epoch,key,contract,binding);}
    }
    private static int raw(Connection c,Fixture f,long epoch,String key,String contract,String binding) throws SQLException {
        try(var q=c.prepareStatement("insert into task.resource_execution(call_key,entry_key,contract_fingerprint,execution_epoch,first_binding_key,entering_executor,entered_at) values(?,?,?,?,?,?,timestamptz '2000-01-01')")) {
            q.setQueryTimeout(8);q.setString(1,f.binding().scope().key());q.setString(2,key);q.setString(3,contract);q.setLong(4,epoch);q.setString(5,binding);q.setObject(6,UUID.randomUUID());return q.executeUpdate();
        }
    }
    private static void outcome(DataSource data,Fixture f,UUID run,UUID checkpoint,String target,String profile,String media,String fingerprint) throws SQLException {
        try(var c=data.getConnection();var q=c.prepareStatement("insert into task.resource_execution_outcome(entry_key,profile,target_fingerprint,run_id,checkpoint_id,content_type,content_fingerprint,observed_at) values(?,?,?,?,?,?,?,timestamptz '2000-01-01')")) {
            q.setQueryTimeout(8);q.setString(1,f.entry(1).fingerprint());q.setString(2,profile);q.setString(3,target);q.setObject(4,run);q.setObject(5,checkpoint);q.setString(6,media);q.setString(7,fingerprint);q.executeUpdate();
        }
    }
    private static void immutable(DataSource data,String table,String operation,String sql) {
        var failure=assertThrows(SQLException.class,()->execute(data,sql));
        assertEquals("42501",failure.getSQLState());
        assertTrue(failure.getMessage().contains("task."+table+" "+operation),"the intended table guard must refuse; an FK or child guard must not mask it: "+failure.getMessage());
    }
    @FunctionalInterface private interface Sql {void run() throws Exception;}
    private static void refused(String state,Sql work){assertEquals(state,assertThrows(SQLException.class,work::run).getSQLState());}
    static void execute(DataSource data,String sql) throws SQLException {try(var c=data.getConnection();var q=c.createStatement()){q.setQueryTimeout(10);q.execute(sql);}}
    private static long scalar(Connection c,String sql) throws SQLException {try(var q=c.createStatement()){q.setQueryTimeout(5);try(var r=q.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}}
    static long count(DataSource data,String sql) throws SQLException {try(var c=data.getConnection()){return scalar(c,sql);}}
    private static String text(DataSource data,String sql) throws SQLException {try(var c=data.getConnection();var q=c.createStatement()){q.setQueryTimeout(5);try(var r=q.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}}
    static Instant databaseNow(DataSource data) throws SQLException {
        try(var c=data.getConnection();var q=c.createStatement()) {
            q.setQueryTimeout(5);try(var r=q.executeQuery("select pg_catalog.clock_timestamp()")){assertTrue(r.next());return r.getTimestamp(1).toInstant();}
        }
    }
    private static List<String> attribution(DataSource data) throws SQLException {
        var rows=new ArrayList<String>();try(var c=data.getConnection();var q=c.createStatement()) {
            for(String table:List.of("resource_call","resource_call_binding","resource_occurrence"))try(var r=q.executeQuery("select row_to_json(t)::text from task."+table+" t order by 1")){while(r.next())rows.add(table+":"+r.getString(1));}
        }return rows;
    }
    @FunctionalInterface private interface Condition {boolean get() throws Exception;}
    private static void await(Condition condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();while(System.nanoTime()<deadline){if(condition.get())return;Thread.sleep(20);}fail("owned SQL condition was not reached before the bound");
    }
    static final class Database implements AutoCloseable {
        final String name="oa_re_facts_"+UUID.randomUUID().toString().replace("-","");final PGSimpleDataSource owner;
        Database() throws SQLException {assertTrue(name.matches("oa_re_facts_[0-9a-f]{32}"));execute(data("postgres"),"create database "+name);owner=data();}
        PGSimpleDataSource data(){return data(name);}
        Flyway flyway(String target){var c=Flyway.configure().dataSource(owner).locations("classpath:db/migration");if(target!=null)c.target(target);return c.load();}
        void migrate(String target){flyway(target).migrate();}
        public void close() throws SQLException {assertTrue(name.matches("oa_re_facts_[0-9a-f]{32}"));execute(data("postgres"),"drop database "+name);}
        private static PGSimpleDataSource data(String name){var ds=new PGSimpleDataSource();ds.setURL(System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/agentic"));ds.setDatabaseName(name);ds.setUser(System.getenv().getOrDefault("DB_USER","postgres"));ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD","postgres"));return ds;}
    }
}
