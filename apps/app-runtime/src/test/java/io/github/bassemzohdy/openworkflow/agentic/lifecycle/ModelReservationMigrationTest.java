package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.sql.*;
import org.junit.jupiter.api.*;

/** Proposed V79 rehearsal only; no Flyway activation, source approval or deployed-role proof. */
class ModelReservationMigrationTest {
    ResourceTaskAllocationMigrationTest support;
    @BeforeEach void create() throws Exception {support=new ResourceTaskAllocationMigrationTest();support.create();support.apply();}
    @AfterEach void drop() throws Exception {if(support!=null)support.close();}
    @Test void conflictingExistingTableRollsBackEveryNewFunctionWithoutTouchingAppliedHistory() throws Exception {
        String before=support.checksums();execute(support.db.owner,"create table task.model_budget_account(marker text);insert into task.model_budget_account values('retain-existing')");
        assertEquals("42P07",assertThrows(SQLException.class,()->ModelCallReservationStoreTest.apply79(support.db.owner)).getSQLState());
        assertEquals("retain-existing",support.value("select marker from task.model_budget_account"));assertEquals(before,support.checksums());
        assertEquals("0",support.value("select count(*)::text from pg_proc where oid=to_regprocedure('task.mb_time_v1(timestamptz)')"));
        assertEquals("0",support.value("select count(*)::text from pg_class where oid=to_regclass('task.model_call_reservation')"));
    }
    @Test void stricterTimeoutsAndHostileSessionNamespaceArePreservedAroundOwnedExpansion() throws Exception {
        try(var c=support.db.owner.getConnection();var s=c.createStatement()) {
            s.execute("set search_path=public,pg_catalog;set lock_timeout='100ms';set statement_timeout='500ms'");
            String path=ResourceTaskAllocationMigrationTest.scalar(c,"show search_path");c.setAutoCommit(false);s.execute(ModelCallReservationStoreTest.proposal79());
            assertEquals("100ms",ResourceTaskAllocationMigrationTest.scalar(c,"show lock_timeout"));
            assertEquals("500ms",ResourceTaskAllocationMigrationTest.scalar(c,"show statement_timeout"));c.commit();
            assertEquals(path,ResourceTaskAllocationMigrationTest.scalar(c,"show search_path"));
            assertEquals("100ms",ResourceTaskAllocationMigrationTest.scalar(c,"show lock_timeout"));
            assertEquals("500ms",ResourceTaskAllocationMigrationTest.scalar(c,"show statement_timeout"));
        }
        support.db.flyway(null).validate();assertEquals(0,support.db.flyway(null).info().pending().length);
        assertEquals("0",support.value("select count(*)::text from public.flyway_schema_history where version in ('78','79')"));
    }
    @Test void unsupportedMigrationIsolationRefusesBeforeCreatingAnyNewObject() throws Exception {
        try(var c=support.db.owner.getConnection();var s=c.createStatement()) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertEquals("25001",assertThrows(SQLException.class,()->s.execute(ModelCallReservationStoreTest.proposal79())).getSQLState());c.rollback();
        }
        assertEquals("0",support.value("select count(*)::text from pg_proc where oid=to_regprocedure('task.mb_time_v1(timestamptz)')"));
        assertEquals("0",support.value("select count(*)::text from pg_class where oid=to_regclass('task.model_budget_account')"));
    }
    @Test void publicMutationDefaultsAreClearedFromBothNewTablesWithoutClaimingExecutorRoleProof() throws Exception {
        execute(support.db.owner,"alter default privileges in schema task grant all on tables to public");
        ModelCallReservationStoreTest.apply79(support.db.owner);
        assertEquals("0",support.value("""
            select count(*)::text from pg_class c cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
            where c.oid in ('task.model_budget_account'::regclass,'task.model_call_reservation'::regclass)
              and a.grantee=0 and a.privilege_type in ('INSERT','UPDATE','DELETE','TRUNCATE')
            """));
    }
    @Test void pinnedBudgetAndNodeHashesMatchJdkUnderHostilePublicFunctionsAndTemporaryTypes() throws Exception {
        var f=fixture(support.db,"a".repeat(64));var a=support.candidate(f);
        String key=ModelBudgetAccount.keyFor(a);var call=new ResourceModelCall(new ResourceExecutionEntry(a.scope(),1),
            java.util.UUID.randomUUID(),"graph/研究𝄞/1","d".repeat(64),100);
        try(var c=support.db.owner.getConnection();var s=c.createStatement()) {
            s.execute("create function public.sha256(pg_catalog.bytea) returns pg_catalog.bytea language sql immutable as $$select pg_catalog.decode(pg_catalog.repeat('0',64),'hex')$$");
            s.execute("create temporary table namespace_probe(i integer);create domain pg_temp.text as pg_catalog.text check(false);set search_path=public,pg_temp,pg_catalog");
            c.setAutoCommit(false);s.execute(ModelCallReservationStoreTest.proposal79());c.commit();
            try(var q=c.prepareStatement("select task.mb_key_v1(?,?,?,?,?,?),task.mn_key_v1(?,?)")) {
                q.setString(1,a.scope().tenant());q.setString(2,a.scope().instanceId().value());q.setString(3,a.scope().owner().kind().name());
                q.setString(4,a.scope().owner().id());q.setString(5,"ALLOCATION");q.setString(6,a.key());q.setString(7,a.scope().key());q.setString(8,call.nodeOccurrence());
                try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(key,r.getString(1));assertEquals(call.key(),r.getString(2));}
            }
        }
        assertEquals("9",support.value("""
            select count(*)::text from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='task'
              and p.proname in ('mb_time_v1','mb_key_v1','mb_fingerprint_v1','mn_key_v1','mn_fingerprint_v1',
                'mr_fingerprint_v1','model_budget_account_guard','model_call_current_v1','model_reservation_insert_guard')
              and p.proconfig @> array['search_path=pg_catalog, pg_temp']
            """));
    }
}
