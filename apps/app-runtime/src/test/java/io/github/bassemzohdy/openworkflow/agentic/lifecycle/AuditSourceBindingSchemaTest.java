package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Executes the actual proposed V75 DDL on cloned audit tables; EVERY fixture DDL/DML is rolled back. No appendOnce consumer. */
@QuarkusTest
class AuditSourceBindingSchemaTest {
    @Inject DataSource data;
    static final String SCOPE="a".repeat(64), KEY="b".repeat(64), CONTENT="c".repeat(64);
    static final List<String> TRANSITIONS=List.of("INTENT","DISPATCH","UNKNOWN","OUTCOME","MANUAL","COMPENSATION");
    record Source(int version,String scope,String key,String transition,long sequence,String content) {
        String identity() {return "as1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.audit.source.v1")
                .number(version).text(scope).text(key).text(transition).number(sequence).toBytes());}
    }
    static Source source(long n) {return new Source(1,SCOPE,KEY,"INTENT",n,CONTENT);}
    @FunctionalInterface interface Work {void run(Connection c,String schema) throws Exception;}
    void fixture(boolean installSource,Work work) throws Exception {
        String schema="gpt_as_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(schema.matches("gpt_as_[0-9a-f]{32}"));
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try {
                sql(c,"create schema "+schema);
                for(var name:List.of("V31__audit_event.sql","V32__audit_append_only.sql","V33__audit_head_log.sql"))
                    sql(c,inSchema(resource("db/migration/"+name),schema));
                if(installSource) install(c,schema);
                work.run(c,schema);
            } finally {c.rollback();}
        }
    }
    static String resource(String path) throws IOException {
        try(var in=AuditSourceBindingSchemaTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in,path);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static void install(Connection c,String s) throws Exception {
        sql(c,inSchema(resource("db/proposed/V75__audit_source_binding.sql"),s));
    }
    static String inSchema(String ddl,String schema) {
        // Change SQL identifiers only; never rewrite the canonical domain string 'oa.audit.source.v1'.
        return ddl.replaceAll("(?<![A-Za-z0-9_.])audit\\.",schema+".");
    }
    static void sql(Connection c,String text) throws SQLException {try(var q=c.createStatement()){q.execute(text);}}
    static AuditEvent event(Connection c,String s,String chain,boolean head) throws SQLException {
        var value=AuditHash.seal(new AuditEvent(UlidGenerator.shared().next(),1,Instant.parse("2026-10-20T08:00:00.123456Z"),
                new Subject(SubjectKind.SERVICE,"source-schema-test"),"effect.intent","effect","fixture",
                Map.of("oa.chain",chain),AuditHash.GENESIS,AuditHash.GENESIS));
        try(var q=c.prepareStatement("insert into "+s+".event(event_id,attributes,seq,occurred_at,actor_kind,actor_id,"
                +"event_type,resource_type,resource_id,previous_hash,hash) values(?,jsonb_build_object('oa.chain',?::text),?,?,?,?,?,?,?,?,?)")) {
            q.setString(1,value.id().value());q.setString(2,chain);q.setLong(3,value.sequence());
            q.setTimestamp(4,Timestamp.from(value.occurredAt()));q.setString(5,value.actor().kind().name());q.setString(6,value.actor().id());
            q.setString(7,value.action());q.setString(8,value.resourceType());q.setString(9,value.resourceId());
            q.setString(10,value.previousHash());q.setString(11,value.hash());q.executeUpdate();
        }
        if(head) try(var q=c.prepareStatement("insert into "+s+".head_log(chain_id,seq,hash) values(?,1,?)")) {
            q.setString(1,chain);q.setString(2,value.hash());q.executeUpdate();
        }
        return value;
    }
    static void bind(Connection c,String s,Source source,AuditEvent event) throws SQLException {
        bind(c,s,source,event,Map.of());
    }
    static void bind(Connection c,String s,Source source,AuditEvent event,Map<String,Object> overrides) throws SQLException {
        var values=new LinkedHashMap<String,Object>();
        values.put("source_key",source.identity());values.put("schema_version",source.version());
        values.put("scope_fingerprint",source.scope());values.put("key_fingerprint",source.key());
        values.put("transition",source.transition());values.put("source_sequence",source.sequence());
        values.put("content_fingerprint",source.content());values.put("event_id",event.id().value());
        values.put("chain_id",event.attributes().get("oa.chain"));values.put("chain_sequence",event.sequence());values.put("hash",event.hash());
        values.putAll(overrides);
        try(var q=c.prepareStatement("insert into "+s+".source_binding("+String.join(",",values.keySet())+") values("
                +String.join(",",Collections.nCopies(values.size(),"?"))+")")) {
            int n=0;for(var v:values.values())q.setObject(++n,v);q.executeUpdate();
        }
    }
    @FunctionalInterface interface SqlWork {void run() throws SQLException;}
    static void refused(Connection c,String state,SqlWork work) throws SQLException {
        var save=c.setSavepoint();
        try {assertEquals(state,assertThrows(SQLException.class,work::run).getSQLState());}
        finally {c.rollback(save);c.releaseSavepoint(save);}
    }
    static long count(Connection c,String s,String table) throws SQLException {
        try(var q=c.createStatement();var r=q.executeQuery("select count(*) from "+s+"."+table)) {r.next();return r.getLong(1);}
    }

    @Test void sqlSourceKeysMatchAgreedCanonicalBytesForAllTransitionsAndLongSequence() throws Exception {
        // Independent Python struct('>i'/'>q'), strict UTF-8 and hashlib vector, not the SQL helper.
        assertEquals("as1-969e797181c935c57170e20b6c16ac02e538a35ae35bab395c89a6d877620db0",source(1).identity());
        fixture(true,(c,s)->{
            for(String transition:TRANSITIONS) for(long n:new long[]{1,Long.MAX_VALUE}) {
                var v=new Source(1,SCOPE,KEY,transition,n,CONTENT);
                assertEquals(new AuditSource(1,SCOPE,KEY,AuditSource.Transition.valueOf(transition),n,CONTENT).key(),v.identity());
                try(var q=c.prepareStatement("select "+s+".source_identity_key(?,?,?,?,?)")) {
                    q.setInt(1,v.version());q.setString(2,v.scope());q.setString(3,v.key());q.setString(4,v.transition());q.setLong(5,v.sequence());
                    try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(v.identity(),r.getString(1));}
                }
                assertEquals(v.identity(),new Source(1,SCOPE,KEY,transition,n,"d".repeat(64)).identity());
            }
        });
    }
    @Test void fullTupleAndReceiptAreRetainedAndAllMutationsIncludingTruncateRefuse() throws Exception {
        fixture(true,(c,s)->{
            var e=event(c,s,"effects",true);var v=source(7);bind(c,s,v,e);
            try(var q=c.createStatement();var r=q.executeQuery("select b.*,e.hash as event_hash from "+s+".source_binding b join "+s+".event e using(event_id)")) {
                assertTrue(r.next());assertEquals(v.identity(),r.getString("source_key"));assertEquals(1,r.getInt("schema_version"));
                assertEquals(SCOPE,r.getString("scope_fingerprint"));assertEquals(KEY,r.getString("key_fingerprint"));
                assertEquals("INTENT",r.getString("transition"));assertEquals(7,r.getLong("source_sequence"));
                assertEquals(CONTENT,r.getString("content_fingerprint"));assertEquals(e.id().value(),r.getString("event_id"));
                assertEquals("effects",r.getString("chain_id"));assertEquals(1,r.getLong("chain_sequence"));
                assertEquals(e.hash(),r.getString("hash"));assertEquals(e.hash(),r.getString("event_hash"));assertNotNull(r.getTimestamp("created_at"));
                assertFalse(r.next());
            }
            refused(c,"42501",()->sql(c,"update "+s+".source_binding set content_fingerprint=content_fingerprint"));
            refused(c,"42501",()->sql(c,"delete from "+s+".source_binding"));
            refused(c,"42501",()->sql(c,"truncate "+s+".source_binding"));
            // Include the child to reach append-only triggers instead of an earlier parent FK TRUNCATE refusal.
            refused(c,"42501",()->sql(c,"truncate "+s+".event,"+s+".head_log,"+s+".source_binding"));
            assertEquals(1,count(c,s,"source_binding"));assertEquals(1,count(c,s,"event"));assertEquals(1,count(c,s,"head_log"));
        });
    }
    @Test void invalidIdentityTupleAndMissingOrChangedEventOrHeadCannotBeBound() throws Exception {
        fixture(true,(c,s)->{
            var e=event(c,s,"effects",true);var v=source(1);
            for(var invalid:List.of(new Source(2,SCOPE,KEY,"INTENT",1,CONTENT),new Source(1,SCOPE,KEY,"INTENT",0,CONTENT),
                    new Source(1,SCOPE,KEY,"intent",1,CONTENT),new Source(1,SCOPE.toUpperCase(Locale.ROOT),KEY,"INTENT",1,CONTENT),
                    new Source(1,SCOPE,KEY,"INTENT",1,"no-digest"))) refused(c,"23514",()->bind(c,s,invalid,e));
            for(var override:List.of(Map.<String,Object>of("source_key","as1-"+"f".repeat(64)),Map.<String,Object>of("chain_id","another"),
                    Map.<String,Object>of("chain_sequence",2L),Map.<String,Object>of("hash","f".repeat(64))))
                refused(c,"23514",()->bind(c,s,v,e,override));
            refused(c,"23503",()->bind(c,s,v,e,Map.of("event_id",UlidGenerator.shared().next().value())));
            var noHead=event(c,s,"without-head",false);refused(c,"23503",()->bind(c,s,v,noHead));
            var badHead=event(c,s,"bad-head",false);
            sql(c,"insert into "+s+".head_log values('bad-head',1,repeat('e',64))");
            refused(c,"23514",()->bind(c,s,v,badHead));assertEquals(0,count(c,s,"source_binding"));
        });
    }
    @Test void oneSourceCannotRebindEventOrContentAndOneEventCannotClaimAnotherSource() throws Exception {
        fixture(true,(c,s)->{
            var a=event(c,s,"first",true);var b=event(c,s,"second",true);var v=source(1);bind(c,s,v,a);
            refused(c,"23505",()->bind(c,s,v,a)); // appendOnce must read back exact duplicates, not append/reseal them.
            refused(c,"23505",()->bind(c,s,v,b));
            refused(c,"23505",()->bind(c,s,new Source(1,SCOPE,KEY,"INTENT",1,"d".repeat(64)),a));
            refused(c,"23505",()->bind(c,s,source(2),a));assertEquals(1,count(c,s,"source_binding"));
        });
    }
    @Test void foreignKeysRetainEventAndHeadEvenWithTheirUserDeleteTriggersDisabled() throws Exception {
        fixture(true,(c,s)->{
            var e=event(c,s,"retained",true);bind(c,s,source(1),e);
            sql(c,"alter table "+s+".event disable trigger event_append_only_delete");
            sql(c,"alter table "+s+".head_log disable trigger head_log_append_only_delete");
            refused(c,"23503",()->sql(c,"delete from "+s+".event"));
            refused(c,"23503",()->sql(c,"delete from "+s+".head_log"));
            refused(c,"0A000",()->sql(c,"truncate "+s+".event"));
            refused(c,"0A000",()->sql(c,"truncate "+s+".head_log"));
            assertEquals(1,count(c,s,"event"));assertEquals(1,count(c,s,"head_log"));
        });
    }
    @Test void installOnExistingChainDoesNotGuessSourcesOrRewriteEventsAndLegacyAppendStillWorks() throws Exception {
        fixture(false,(c,s)->{
            var e=event(c,s,"legacy",true);install(c,s);
            assertEquals(1,count(c,s,"event"));assertEquals(1,count(c,s,"head_log"));assertEquals(0,count(c,s,"source_binding"));
            try(var q=c.createStatement();var r=q.executeQuery("select hash,seq from "+s+".event")) {
                assertTrue(r.next());assertEquals(e.hash(),r.getString(1));assertEquals(e.sequence(),r.getLong(2));
            }
            event(c,s,"new-legacy-append",true);assertEquals(2,count(c,s,"event"));assertEquals(0,count(c,s,"source_binding"));
        });
    }
}
