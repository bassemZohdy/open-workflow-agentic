package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Actual proposed V76 on rollback-only cloned metadata. No graph, JWT verification or fresh-callback gate is claimed. */
@QuarkusTest
class ResourceExecutionSchemaTest {
    @Inject DataSource data;
    @Inject InstanceStore registry;
    @Inject DelegationGrants grants;
    @Inject JdbcResourceCallBindings bindings;
    static final Subject ALICE=new Subject(SubjectKind.USER,"alice"), WORKFLOW=new Subject(SubjectKind.WORKFLOW,"execution-schema");
    static final String PROFILE="langgraph4j-postgres-saver-1.9.5-v1.1-object-stream", MEDIA="application/octet-stream";
    final List<InstanceRecord> rows=new ArrayList<>();
    record Fixture(InstanceRecord row,ResourceCallBinding binding) { }
    static Instant now() {return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    Fixture fixture(String target) {
        var req=new StartRequest("execution-"+UUID.randomUUID(),ALICE,new DefinitionRef("agentic/execution-schema-test","1"),Map.of(),"start");
        var r=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),req,now()),"test.start").record();rows.add(r);
        r=registry.replace(r.successor(now(),InstanceStatus.RUNNING,"engine-"+r.id(),1,null,null),r.version(),"test.running",ALICE);
        var scope=new ResourceCallScope(r.tenant(),r.id(),ALICE,"app-𝄞",r.engineInstanceId(),"agentic","execution-schema-test","1",
                "/do/0/研究",1,"root/foreach/é","agent-run","run",target,"b".repeat(64));
        var grant=grants.grant(r.tenant(),ALICE,ALICE,WORKFLOW,Set.of("oa.agent.run"),now().plusSeconds(3600),null);
        var expiry=now().plusSeconds(120);String issuer="https://execution-idp.test",jti=UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(data).bind(issuer,jti,new IssuedTokenBindings.Binding(grant.id(),r.tenant(),expiry));
        var b=new ResourceCallBinding(issuer,jti,grant.id(),scope,expiry.minusSeconds(120),expiry);bindings.bind(b);
        return new Fixture(r,b);
    }
    ResourceCallBinding anotherCredential(Fixture f) {
        var b=f.binding();String jti=UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(data).bind(b.issuer(),jti,new IssuedTokenBindings.Binding(b.issuingGrantId(),b.scope().tenant(),b.expiresAt()));
        var other=new ResourceCallBinding(b.issuer(),jti,b.issuingGrantId(),b.scope(),b.issuedAt(),b.expiresAt());bindings.bind(other);return other;
    }
    @AfterEach void finishRegistryRows() {
        for(var original:rows) {
            var r=registry.find(original.tenant(),original.id()).orElseThrow();
            if(!r.status().isTerminal()) registry.replace(r.successor(now(),InstanceStatus.COMPLETED,r.engineInstanceId(),r.startAttempts(),null,null),
                    r.version(),"test.complete",ALICE);
        }
    }
    @FunctionalInterface interface Work {void run(Connection c,String s) throws Exception;}
    void proposed(List<Fixture> fixtures,Work work) throws Exception {
        String s="gpt_re_"+UUID.randomUUID().toString().replace("-","");assertTrue(s.matches("gpt_re_[0-9a-f]{32}"));
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try {
                sql(c,"create schema "+s);
                // Actual column types/data, deliberately without copying existing parent triggers or foreign keys.
                // No shared parent table is altered or cleared; only this UUID schema is ever mutated.
                for(var table:List.of("instance","resource_call","resource_call_binding")) {
                    sql(c,"create table "+s+"."+table+" as select * from task."+table+" where false");
                    sql(c,"alter table "+s+"."+table+" add primary key ("+(table.equals("instance")?"id":table.equals("resource_call")?"call_key":"binding_key")+")");
                }
                for(var f:fixtures) {
                    copy(c,s,"instance","id",f.row().id().value());copy(c,s,"resource_call","call_key",f.binding().scope().key());
                    copy(c,s,"resource_call_binding","call_key",f.binding().scope().key());
                }
                sql(c,proposal().replaceAll("(?<![A-Za-z0-9_.])task\\.",s+"."));
                work.run(c,s);
            } finally {c.rollback();}
        }
    }
    static String proposal() throws IOException {
        try(var in=ResourceExecutionSchemaTest.class.getClassLoader().getResourceAsStream("db/proposed/V76__resource_execution_facts.sql")) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static void copy(Connection c,String s,String table,String column,String value) throws SQLException {
        try(var q=c.prepareStatement("insert into "+s+"."+table+" select * from task."+table+" where "+column+"=?")) {
            q.setString(1,value);q.executeUpdate();
        }
    }
    static void sql(Connection c,String text) throws SQLException {try(var q=c.createStatement()){q.execute(text);}}
    static void insert(Connection c,String s,String table,LinkedHashMap<String,Object> values) throws SQLException {
        try(var q=c.prepareStatement("insert into "+s+"."+table+"("+String.join(",",values.keySet())+") values("
                +String.join(",",Collections.nCopies(values.size(),"?"))+")")) {
            int n=0;for(var v:values.values())q.setObject(++n,v);q.executeUpdate();
        }
    }
    static String entryKey(ResourceCallBinding b,long epoch) {
        return "re1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.execution-entry.v1")
                .text(b.scope().key()).text(b.scope().fingerprint()).number(epoch).toBytes());
    }
    static void enter(Connection c,String s,ResourceCallBinding b,long epoch,Map<String,Object> override) throws SQLException {
        var v=new LinkedHashMap<String,Object>();v.put("call_key",b.scope().key());v.put("entry_key",entryKey(b,epoch));
        v.put("contract_fingerprint",b.scope().fingerprint());v.put("execution_epoch",epoch);v.put("first_binding_key",b.key());
        v.put("entering_executor",UUID.randomUUID());v.putAll(override);insert(c,s,"resource_execution",v);
    }
    static void outcome(Connection c,String s,ResourceCallBinding b,UUID run,UUID checkpoint,Map<String,Object> override) throws SQLException {
        var v=new LinkedHashMap<String,Object>();v.put("entry_key",entryKey(b,1));v.put("profile",PROFILE);
        v.put("target_fingerprint",b.scope().targetFingerprint());v.put("run_id",run);v.put("checkpoint_id",checkpoint);
        v.put("content_type",MEDIA);v.put("content_fingerprint","d".repeat(64));v.putAll(override);insert(c,s,"resource_execution_outcome",v);
    }
    @FunctionalInterface interface SqlWork {void run() throws SQLException;}
    static void refused(Connection c,String state,SqlWork work) throws SQLException {
        var save=c.setSavepoint();
        try {assertEquals(state,assertThrows(SQLException.class,work::run).getSQLState());}
        finally {c.rollback(save);c.releaseSavepoint(save);}
    }
    static long count(Connection c,String s,String table) throws SQLException {
        try(var q=c.createStatement();var r=q.executeQuery("select count(*) from "+s+"."+table)){r.next();return r.getLong(1);}
    }
    static String contract(Connection c,String s,Fixture f) throws SQLException {
        try(var q=c.prepareStatement("select "+s+".resource_call_data_contract(invocation) from "+s+".resource_call invocation where instance_id=?")) {
            q.setString(1,f.row().id().value());try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    @Test void canonicalUnicodeCallAndFullImmutableEntryAndOutcomeRoundTrip() throws Exception {
        var f=fixture("a".repeat(64));var b=f.binding();var run=UUID.randomUUID();var checkpoint=UUID.randomUUID();
        proposed(List.of(f),(c,s)->{
            assertEquals(b.scope().fingerprint(),contract(c,s,f));enter(c,s,b,1,Map.of());outcome(c,s,b,run,checkpoint,Map.of());
            try(var q=c.createStatement();var r=q.executeQuery("select e.*,o.* from "+s+".resource_execution e join "+s+".resource_execution_outcome o using(entry_key)")) {
                assertTrue(r.next());assertEquals(entryKey(b,1),r.getString("entry_key"));assertEquals(b.scope().key(),r.getString("call_key"));
                assertEquals(b.scope().fingerprint(),r.getString("contract_fingerprint"));assertEquals(1,r.getLong("execution_epoch"));
                assertEquals(b.key(),r.getString("first_binding_key"));assertNotNull(r.getObject("entering_executor",UUID.class));
                assertNotNull(r.getTimestamp("entered_at"));assertEquals(PROFILE,r.getString("profile"));
                assertEquals(b.scope().targetFingerprint(),r.getString("target_fingerprint"));assertEquals(run,r.getObject("run_id",UUID.class));
                assertEquals(checkpoint,r.getObject("checkpoint_id",UUID.class));assertEquals(MEDIA,r.getString("content_type"));
                assertEquals("d".repeat(64),r.getString("content_fingerprint"));assertNotNull(r.getTimestamp("observed_at"));assertFalse(r.next());
            }
            for(var table:List.of("resource_execution_outcome","resource_execution")) {
                refused(c,"42501",()->sql(c,"update "+s+"."+table+" set entry_key=entry_key"));
                refused(c,"42501",()->sql(c,"delete from "+s+"."+table));
            }
            refused(c,"42501",()->sql(c,"truncate "+s+".resource_execution_outcome"));
            refused(c,"42501",()->sql(c,"truncate "+s+".resource_execution,"+s+".resource_execution_outcome"));
            assertEquals(1,count(c,s,"resource_execution"));assertEquals(1,count(c,s,"resource_execution_outcome"));
        });
    }
    @Test void anotherCredentialEpochOrConstructedEntryCannotCreateSecondEntry() throws Exception {
        var f=fixture("a".repeat(64));var b=f.binding();var fresh=anotherCredential(f);
        proposed(List.of(f),(c,s)->{
            enter(c,s,b,1,Map.of());
            refused(c,"23505",()->enter(c,s,b,1,Map.of()));refused(c,"23505",()->enter(c,s,b,2,Map.of()));
            refused(c,"23505",()->enter(c,s,fresh,1,Map.of()));
            refused(c,"23514",()->enter(c,s,b,1,Map.of("entry_key","re1-"+"f".repeat(64))));
            refused(c,"23514",()->enter(c,s,b,1,Map.of("contract_fingerprint","f".repeat(64))));
            refused(c,"23514",()->enter(c,s,b,0,Map.of()));
            assertEquals(1,count(c,s,"resource_execution"));assertEquals(0,count(c,s,"resource_execution_outcome"));
        });
    }
    @Test void fullSourceContractRecomputationRefusesCorruptKeysOwnerCoordinatesAndTarget() throws Exception {
        var f=fixture("a".repeat(64));proposed(List.of(f),(c,s)->{
            for(var assignment:List.of("fingerprint=repeat('f',64)","call_key='rc1-'||repeat('f',64)","owner_kind='AGENT'",
                    "logical_occurrence='other'","task_position='other'","iteration=2","target_fingerprint=repeat('c',64)"))
                refused(c,"23514",()->{sql(c,"update "+s+".resource_call set "+assignment);contract(c,s,f);});
            assertEquals(f.binding().scope().fingerprint(),contract(c,s,f));
        });
    }
    @Test void terminalExpiredFutureDeadlineOrWrongBindingMetadataCannotEnter() throws Exception {
        var f=fixture("a".repeat(64));var other=fixture("a".repeat(64));var b=f.binding();
        proposed(List.of(f,other),(c,s)->{
            for(var assignment:List.of("status='CANCEL_REQUESTED'","owner_kind='AGENT'","engine_instance_id='other'",
                    "definition_version='other'","wait_deadline=clock_timestamp()-interval '1 second'"))
                refused(c,"23514",()->{sql(c,"update "+s+".instance set "+assignment);enter(c,s,b,1,Map.of());});
            for(var assignment:List.of("expires_at=clock_timestamp()-interval '1 second'","issued_at=clock_timestamp()+interval '1 minute'"))
                refused(c,"23514",()->{sql(c,"update "+s+".resource_call_binding set "+assignment);enter(c,s,b,1,Map.of());});
            refused(c,"23514",()->enter(c,s,b,1,Map.of("first_binding_key",other.binding().key())));
            refused(c,"23503",()->enter(c,s,b,1,Map.of("first_binding_key","rb1-"+"f".repeat(64))));
            assertEquals(0,count(c,s,"resource_execution"));
        });
    }
    @Test void historicalFactsRemainAfterEndButPhysicalResultCannotRebindWithinApprovedTarget() throws Exception {
        var f=fixture("a".repeat(64));var second=fixture("a".repeat(64));var anotherTarget=fixture("c".repeat(64));
        UUID run=UUID.randomUUID(),checkpoint=UUID.randomUUID();
        proposed(List.of(f,second,anotherTarget),(c,s)->{
            for(var item:List.of(f,second,anotherTarget))enter(c,s,item.binding(),1,Map.of());
            sql(c,"update "+s+".instance set status='COMPLETED'");sql(c,"update "+s+".resource_call_binding set expires_at=clock_timestamp()-interval '1 second'");
            outcome(c,s,f.binding(),run,checkpoint,Map.of()); // Data fact, NEVER permission to release private output.
            refused(c,"23505",()->outcome(c,s,second.binding(),run,UUID.randomUUID(),Map.of()));
            refused(c,"23505",()->outcome(c,s,second.binding(),UUID.randomUUID(),checkpoint,Map.of()));
            refused(c,"23505",()->outcome(c,s,f.binding(),UUID.randomUUID(),UUID.randomUUID(),Map.of()));
            // Copied independent target stores may contain the same UUIDs: namespace the identity by approved target.
            outcome(c,s,anotherTarget.binding(),run,checkpoint,Map.of());assertEquals(2,count(c,s,"resource_execution_outcome"));
        });
    }
    @Test void missingEntryWrongTargetProfileOrContentRefuseAndReferencesRemainRetained() throws Exception {
        var f=fixture("a".repeat(64));var b=f.binding();var run=UUID.randomUUID();var checkpoint=UUID.randomUUID();
        proposed(List.of(f),(c,s)->{
            refused(c,"23503",()->outcome(c,s,b,run,checkpoint,Map.of()));enter(c,s,b,1,Map.of());
            for(var override:List.of(Map.<String,Object>of("target_fingerprint","c".repeat(64)),Map.<String,Object>of("profile","unknown"),
                    Map.<String,Object>of("content_type","application/json"),Map.<String,Object>of("content_fingerprint","F".repeat(64))))
                refused(c,"23514",()->outcome(c,s,b,run,checkpoint,override));
            outcome(c,s,b,run,checkpoint,Map.of());
            refused(c,"23503",()->sql(c,"delete from "+s+".resource_call"));
            refused(c,"23503",()->sql(c,"delete from "+s+".resource_call_binding"));
            refused(c,"0A000",()->sql(c,"truncate "+s+".resource_call"));
            refused(c,"0A000",()->sql(c,"truncate "+s+".resource_call_binding"));
            assertEquals(1,count(c,s,"resource_execution"));assertEquals(1,count(c,s,"resource_execution_outcome"));
        });
    }
}
