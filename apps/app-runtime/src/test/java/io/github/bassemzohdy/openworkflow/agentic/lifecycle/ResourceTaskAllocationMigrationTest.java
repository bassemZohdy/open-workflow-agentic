package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** Actual V77 databases plus explicit PROPOSED V78. No Flyway activation, trusted approval source or dispatch. */
class ResourceTaskAllocationMigrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Subject AGENT=new Subject(SubjectKind.AGENT,"allocation-研究𝄞");
    Database db;
    @BeforeEach void create() throws Exception {
        db=new Database();try(var c=db.owner.getConnection()){assertEquals(db.name,scalar(c,"select current_database()"));}
        db.migrate(null);
    }
    @AfterEach void close() throws Exception {if(db!=null)db.close();}
    static String proposal() throws Exception {
        try(var in=ResourceTaskAllocationMigrationTest.class.getClassLoader().getResourceAsStream("db/proposed/V78__resource_task_allocation.sql")) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static String scalar(Connection c,String sql) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}
    }
    String value(String sql) throws SQLException {try(var c=db.owner.getConnection()){return scalar(c,sql);}}
    static void apply(Connection c) throws Exception {try(var s=c.createStatement()){s.setQueryTimeout(10);s.execute(proposal());}}
    void apply() throws Exception {
        try(var c=db.owner.getConnection()) {
            c.setAutoCommit(false);
            try{apply(c);c.commit();}catch(Exception|Error e){c.rollback();throw e;}
        }
    }
    static void entry(Connection c,Fixture f) throws SQLException {
        try(var q=c.prepareStatement("""
            insert into task.resource_execution(call_key,entry_key,contract_fingerprint,execution_epoch,
                first_binding_key,entering_executor,entered_at) values(?,?,?,?,?,?,clock_timestamp())
            """)) {
            q.setString(1,f.binding().scope().key());q.setString(2,f.entry(1).fingerprint());q.setString(3,f.binding().scope().fingerprint());
            q.setLong(4,1);q.setString(5,f.binding().key());q.setObject(6,UUID.randomUUID());q.executeUpdate();
        }
    }
    void entry(Fixture f) throws SQLException {try(var c=db.owner.getConnection()){entry(c,f);}}
    String history() throws SQLException {return value("select coalesce(json_agg(e order by call_key)::text,'[]') from task.resource_execution e");}
    String checksums() throws SQLException {return value("select string_agg(version||':'||checksum::text,',' order by version) from public.flyway_schema_history where version in ('76','77')");}
    ResourceTaskAllocation candidate(Fixture f) throws Exception {
        var clock=Clock.fixed(databaseNow(db.owner),ZoneOffset.UTC);var ids=UlidGenerator.shared();
        var store=new DelegationGrantStore(db.owner);var root=store.find(f.binding().issuingGrantId()).orElseThrow();
        // Fixture-only issuing/delegation metadata, not registered model approval or model-call scope permission.
        var grants=new DelegationGrants(store,new IdentityAudit(e->{},clock,ids),clock,ids,Duration.ofHours(2));
        var child=grants.delegate(root.id(),root.agent(),AGENT,Set.of("oa.agent.run"),clock.instant().plusSeconds(900),null);
        var scope=f.binding().scope();
        var task=new AgentTaskContract(ids.next(),"resource-request-sha256:"+scope.requestFingerprint()+" / 研究𝄞",
                Set.of("tool:research"),Set.of("data:public"),RiskTier.HIGH,new ExecutionLimits(5,Duration.ofSeconds(120),5000),scope.owner(),child.id());
        var profile=new ApprovedModelProfile("profile-研究𝄞","1","c".repeat(64),AGENT,"model",3,1000,Duration.ofSeconds(10,123),2048);
        return new ResourceTaskAllocation(scope,null,task,profile);
    }
    static String contract(ResourceTaskAllocation a) throws Exception {
        var t=a.contract();return JSON.writeValueAsString(Map.of("id",t.id().value(),"goal",t.goal(),"permittedTools",t.permittedTools(),
                "permittedDataScopes",t.permittedDataScopes(),"riskTier",t.riskTier().name(),"limits",Map.of("maxIterations",t.limits().maxIterations(),
                        "maxWallClockSeconds",t.limits().maxWallClock().getSeconds(),"maxCostMicros",t.limits().maxCostMicros()),
                "requestingSubject",Map.of("kind",t.requestingSubject().kind().name(),"id",t.requestingSubject().id()),"delegationGrantId",t.delegationGrantId().value()));
    }
    static String profile(ResourceTaskAllocation a) throws Exception {
        var p=a.modelProfile();return JSON.writeValueAsString(Map.of("id",p.id(),"version",p.version(),"configurationFingerprint",p.configurationFingerprint(),
                "agent",Map.of("kind",p.agent().kind().name(),"id",p.agent().id()),"modelId",p.modelId(),"maxModelCalls",p.maxModelCalls(),
                "perCallCeilingMicros",p.perCallCeilingMicros(),"maxCallSeconds",p.maxCallTime().getSeconds(),"maxCallNanos",p.maxCallTime().getNano(),"maxOutputTokens",p.maxOutputTokens()));
    }
    void allocate(ResourceTaskAllocation a) throws Exception {
        try(var c=db.owner.getConnection();var q=c.prepareStatement("""
            insert into task.resource_task_allocation(allocation_key,call_key,call_fingerprint,parent_task_id,
                contract_id,contract,contract_fingerprint,model_profile,profile_fingerprint,fingerprint)
            values(?,?,?,?,?,?::jsonb,?,?::jsonb,?,?)
            """)) {
            q.setString(1,a.key());q.setString(2,a.scope().key());q.setString(3,a.scope().fingerprint());q.setString(4,null);
            q.setString(5,a.contract().id().value());q.setString(6,contract(a));q.setString(7,AgentTaskContractFingerprint.of(a.contract()));
            q.setString(8,profile(a));q.setString(9,a.modelProfile().fingerprint());q.setString(10,a.fingerprint());q.executeUpdate();
        }
    }
    @Test void proposalStaysOutsideFlywayAndHistoricalFactsArePreservedWithoutFakeAllocation() throws Exception {
        var f=fixture(db,"a".repeat(64));entry(f);String before=history(),sums=checksums();
        assertEquals(0,db.flyway(null).info().pending().length);apply();db.flyway(null).validate();
        assertEquals(before,history());assertEquals(sums,checksums());
        assertEquals(0,count(db.owner,"select count(*) from public.flyway_schema_history where version in ('78','79')"));
        assertEquals(0,count(db.owner,"select count(*) from task.resource_task_allocation"));
        assertEquals(1,count(db.owner,"select count(*) from task.resource_execution_allocation_exemption"));
        assertEquals("false",value("select convalidated::text from pg_constraint where conname='resource_execution_allocation_fk'"));
        assertEquals("true",value("select (e.entry_key=x.entry_key and e.contract_fingerprint=x.contract_fingerprint and e.execution_epoch=x.execution_epoch and e.entering_executor=x.entering_executor and e.entered_at=x.entered_at)::text from task.resource_execution e join task.resource_execution_allocation_exemption x using(call_key)"));
        assertEquals("23514",assertThrows(SQLException.class,()->allocate(candidate(f))).getSQLState(),"historical execution cannot be retrofitted with invented allocation");
    }
    @Test void newUnallocatedEntryRefusesButExplicitMatchingMetadataAllocationPermitsDataInsert() throws Exception {
        var f=fixture(db,"a".repeat(64));apply();var missing=assertThrows(SQLException.class,()->entry(f));
        assertEquals("23503",missing.getSQLState());assertTrue(missing.getMessage().contains("resource_execution_allocation_fk"));
        var a=candidate(f);allocate(a);entry(f);
        assertEquals(1,count(db.owner,"select count(*) from task.resource_execution"));
        assertEquals(0,count(db.owner,"select count(*) from task.resource_execution_allocation_exemption"));
        try(var c=db.owner.getConnection();var q=c.prepareStatement("select task.ra_contract_hash_v1(?::jsonb),task.ra_profile_hash_v1(?::jsonb)")) {
            q.setString(1,contract(a));q.setString(2,profile(a));try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(AgentTaskContractFingerprint.of(a.contract()),r.getString(1));assertEquals(a.modelProfile().fingerprint(),r.getString(2));}
        }
        // A present FK/allocation is only metadata, not fresh approval, budgets or execution permission.
    }
    @Test void theExactExemptionInventoryCannotBeExtendedRewrittenDeletedOrTruncated() throws Exception {
        var f=fixture(db,"a".repeat(64));entry(f);apply();String before=history();
        for(String sql:List.of("insert into task.resource_execution_allocation_exemption select * from task.resource_execution_allocation_exemption",
                "update task.resource_execution_allocation_exemption set execution_epoch=execution_epoch", "delete from task.resource_execution_allocation_exemption",
                "truncate task.resource_execution_allocation_exemption")) {
            var refused=assertThrows(SQLException.class,()->execute(db.owner,sql));assertEquals("42501",refused.getSQLState());
            assertTrue(refused.getMessage().contains("task.resource_execution_allocation_exemption"));
        }
        var parent=assertThrows(SQLException.class,()->execute(db.owner,"update task.resource_execution set execution_epoch=execution_epoch"));
        assertEquals("42501",parent.getSQLState());assertTrue(parent.getMessage().contains("task.resource_execution"));
        assertEquals(before,history());assertEquals(1,count(db.owner,"select count(*) from task.resource_execution_allocation_exemption"));
    }
    @Test void conflictingExistingAllocationTableRollsBackWithoutPartialFunctionsOrInventory() throws Exception {
        var f=fixture(db,"a".repeat(64));entry(f);String before=history(),sums=checksums();
        execute(db.owner,"create table task.resource_task_allocation(marker text);insert into task.resource_task_allocation values('keep-existing')");
        assertThrows(SQLException.class,this::apply);assertEquals("keep-existing",value("select marker from task.resource_task_allocation"));
        assertEquals(before,history());assertEquals(sums,checksums());
        assertEquals(0,count(db.owner,"select count(*) from pg_proc where oid=to_regprocedure('task.ra_text_v1(text)')"));
        assertEquals(0,count(db.owner,"select count(*) from pg_class where oid=to_regclass('task.resource_execution_allocation_exemption')"));
    }
    @Test void localSearchPathAndBoundsRestoreAndStricterTimeoutsAreNeverWidened() throws Exception {
        try(var c=db.owner.getConnection()) {
            try(var s=c.createStatement()){s.execute("set search_path=public,pg_catalog;set lock_timeout='100ms';set statement_timeout='500ms'");}
            String path=scalar(c,"show search_path");c.setAutoCommit(false);apply(c);
            assertEquals("100ms",scalar(c,"show lock_timeout"));assertEquals("500ms",scalar(c,"show statement_timeout"));
            c.commit();assertEquals(path,scalar(c,"show search_path"));
            assertEquals("100ms",scalar(c,"show lock_timeout"));assertEquals("500ms",scalar(c,"show statement_timeout"));
        }
    }
    @Test void unsupportedSnapshotIsolationRefusesBeforeAnyMigrationDdl() throws Exception {
        try(var c=db.owner.getConnection()) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertEquals("25001",assertThrows(SQLException.class,()->apply(c)).getSQLState());c.rollback();
        }
        assertEquals(0,count(db.owner,"select count(*) from pg_class where oid=to_regclass('task.resource_task_allocation')"));
        assertEquals(0,count(db.owner,"select count(*) from pg_proc where oid=to_regprocedure('task.ra_text_v1(text)')"));
    }
    @Test void pinnedFunctionsIgnoreHostileSessionFunctionsAndTemporaryTextTypes() throws Exception {
        var f=fixture(db,"a".repeat(64));var a=candidate(f);
        try(var c=db.owner.getConnection();var s=c.createStatement()) {
            s.execute("create function public.sha256(pg_catalog.bytea) returns pg_catalog.bytea language sql immutable as $$select pg_catalog.decode(pg_catalog.repeat('0',64),'hex')$$");
            s.execute("create temporary table type_namespace_probe(i integer);create domain pg_temp.text as pg_catalog.text check(false);set search_path=public,pg_temp,pg_catalog");
            c.setAutoCommit(false);apply(c);c.commit();
            try(var q=c.prepareStatement("select task.ra_contract_hash_v1(?::pg_catalog.jsonb)")) {
                q.setString(1,contract(a));try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(AgentTaskContractFingerprint.of(a.contract()),r.getString(1));}
                c.setAutoCommit(false);
                try {
                    s.execute("alter function task.ra_contract_hash_v1(pg_catalog.jsonb) reset search_path");
                    // Actual negative control: the same valid input fails or hashes differently without the runtime pin.
                    try(var r=q.executeQuery()){assertTrue(r.next());assertNotEquals(AgentTaskContractFingerprint.of(a.contract()),r.getString(1));}
                    catch(SQLException shadowed){assertNotEquals("00000",shadowed.getSQLState());}
                } finally {c.rollback();}
            }
        }
        allocate(a);entry(f);
    }
    @Test void aWriterCommittingDuringTheMigrationLockWaitIsIncludedInTheImmutableInventory() throws Exception {
        var f=fixture(db,"a".repeat(64));var pid=new AtomicInteger();var started=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try(var writer=db.owner.getConnection()) {
            writer.setAutoCommit(false);entry(writer,f);
            var migration=pool.submit(()->{try(var c=db.owner.getConnection()){c.setAutoCommit(false);pid.set(Integer.parseInt(scalar(c,"select pg_backend_pid()")));started.countDown();apply(c);c.commit();}return null;});
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));awaitMigrationLock(pid.get());writer.commit();migration.get(10,TimeUnit.SECONDS);
                assertEquals(1,count(db.owner,"select count(*) from task.resource_execution_allocation_exemption"));
                assertEquals(f.binding().scope().key(),value("select call_key from task.resource_execution_allocation_exemption"));
            } finally {writer.rollback();}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    void awaitMigrationLock(int pid) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(1).toNanos();
        while(System.nanoTime()<end) {
            if(count(db.owner,"select count(*) from pg_locks where pid="+pid+" and relation='task.resource_execution'::regclass and not granted")>0)return;
            Thread.sleep(10);
        }
        fail("migration did not reach the real execution-table lock wait");
    }
    @Test void boundedLockFailureLeavesOldHistoryAndNoPartialMigration() throws Exception {
        var retained=fixture(db,"a".repeat(64));entry(retained);String before=history();var held=fixture(db,"a".repeat(64));
        try(var writer=db.owner.getConnection();var c=db.owner.getConnection()) {
            writer.setAutoCommit(false);entry(writer,held);c.setAutoCommit(false);
            try(var s=c.createStatement()){s.execute("set local lock_timeout='100ms'");}
            long start=System.nanoTime();var timeout=assertThrows(SQLException.class,()->apply(c));
            assertEquals("55P03",timeout.getSQLState());assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<3000);
            c.rollback();writer.rollback();
        }
        assertEquals(before,history());
        assertEquals(0,count(db.owner,"select count(*) from pg_class where oid=to_regclass('task.resource_task_allocation')"));
        assertEquals(0,count(db.owner,"select count(*) from pg_class where oid=to_regclass('task.resource_execution_allocation_exemption')"));
    }
}
