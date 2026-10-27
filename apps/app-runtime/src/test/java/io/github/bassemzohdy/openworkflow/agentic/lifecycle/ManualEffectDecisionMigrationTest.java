package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Private V80 proposal rehearsal only; neither Flyway activation nor a production operator route. */
class ManualEffectDecisionMigrationTest {
    ResourceTaskAllocationMigrationTest support;
    @BeforeEach void create() throws Exception {support=new ResourceTaskAllocationMigrationTest();support.create();support.apply();}
    @AfterEach void drop() throws Exception {if(support!=null)support.close();}

    static String proposal() throws Exception {
        try(var in=ManualEffectDecisionMigrationTest.class.getClassLoader().getResourceAsStream("db/proposed/V80__manual_effect_decisions.sql")) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static void apply(DataSource source) throws Exception {
        try(var c=source.getConnection()) {c.setAutoCommit(false);try(var s=c.createStatement()){s.setQueryTimeout(10);s.execute(proposal());c.commit();}catch(Exception|Error failure){c.rollback();throw failure;}}
    }

    @Test void proposalCreatesImmutableDecisionAndApplyTablesWithoutFlywayActivation() throws Exception {
        apply(support.db.owner);
        assertEquals("0",support.value("select count(*)::text from public.flyway_schema_history where version='80'"));
        assertEquals("1",support.value("select count(*)::text from pg_class where oid='effect.manual_decision'::regclass"));
        assertEquals("1",support.value("select count(*)::text from pg_class where oid='effect.manual_decision_applied'::regclass"));
        assertEquals("0",support.value("""
            select count(*)::text from pg_class c cross join lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
            where c.oid in ('effect.manual_decision'::regclass,'effect.manual_decision_applied'::regclass)
              and a.grantee=0 and a.privilege_type in ('INSERT','UPDATE','DELETE','TRUNCATE')
            """));
        support.db.flyway(null).validate();assertEquals(0,support.db.flyway(null).info().pending().length);
    }

    @Test void conflictingObjectRollsBackTheWholeProposalWithoutChangingAppliedHistory() throws Exception {
        String before=support.checksums();execute(support.db.owner,"create table effect.manual_decision(marker text);insert into effect.manual_decision values('retain-existing')");
        assertEquals("42P07",assertThrows(SQLException.class,()->apply(support.db.owner)).getSQLState());
        assertEquals("retain-existing",support.value("select marker from effect.manual_decision"));
        assertEquals(before,support.checksums());
        assertEquals("0",support.value("select count(*)::text from pg_proc where oid=to_regprocedure('effect.manual_decision_insert_guard()')"));
        assertEquals("0",support.value("select count(*)::text from pg_class where oid=to_regclass('effect.manual_decision_applied')"));
    }
}
