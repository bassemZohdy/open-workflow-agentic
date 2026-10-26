package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;

/** Actual V77 in UUID-owned PG databases. Retained DATA/ACK checks are not production delivery or permission. */
class EffectAuditFactsMigrationTest {
    record Fact(long id,long initial,EffectScope scope,EffectCommand command,AuditSource source,AuditEvent draft) { }
    static String hash(String domain,String... parts) {
        var e=new CanonicalEncoder(domain);for(String p:parts)e.text(p);return CanonicalEncoder.sha256Hex(e.toBytes());
    }
    static void sql(Connection c,String text,Object... args) throws SQLException {
        try(var q=c.prepareStatement(text)){q.setQueryTimeout(10);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.execute();}
    }
    static String value(Connection c,String text,Object... args) throws SQLException {
        try(var q=c.prepareStatement(text)){q.setQueryTimeout(10);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);
            try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}}
    }
    static String scopeHash(EffectScope s){return hash("oa.effect.audit-scope.v1",s.tenant(),s.instanceId().value(),s.owner().kind().name(),s.owner().id());}
    static EffectScope scope(Fixture f){var s=f.binding().scope();return new EffectScope(s.tenant(),s.instanceId(),s.owner());}
    static Fact initial(Connection c,EffectScope scope,boolean includeSource) throws Exception {
        return initial(c,scope,scope.command("capture","occurrence-1","approved-target","{\"amount\":10}"),null,"EFFECT",includeSource);
    }
    static Fact initial(Connection c,EffectScope scope,EffectCommand cmd,Long parent,String purpose,boolean includeSource) throws Exception {
        var k=cmd.key();
        sql(c,"insert into effect.ledger(thread_name,node_id,idempotency_key,target,state,recorded_at,request_fingerprint,purpose) values(?,?,?,?,'INTENDED',clock_timestamp(),?,?)",
                k.threadId().value(),k.nodeId(),k.idempotencyKey(),cmd.target(),cmd.requestFingerprint(),purpose);
        long id=Long.parseLong(value(c,"insert into effect.event(thread_name,node_id,idempotency_key,from_state,to_state,reason) values(?,?,?,null,'INTENDED','effect.intended') returning id",k.threadId().value(),k.nodeId(),k.idempotencyKey()));
        sql(c,"insert into effect.audit_scope(thread_name,node_id,idempotency_key,tenant,registry_instance_id,owner_kind,owner_id,scope_fingerprint,initial_event_id,parent_initial_event_id) values(?,?,?,?,?,?,?,?,?,?)",
                k.threadId().value(),k.nodeId(),k.idempotencyKey(),scope.tenant(),scope.instanceId().value(),scope.owner().kind().name(),scope.owner().id(),scopeHash(scope),id,parent);
        Fact f=fact(c,id,id,scope,cmd,AuditSource.Transition.INTENT,null,"INTENDED");if(includeSource)source(c,f,null);return f;
    }
    static Fact fact(Connection c,long id,long initial,EffectScope s,EffectCommand cmd,AuditSource.Transition transition,String from,String to) throws Exception {
        var k=cmd.key();String sf=scopeHash(s),kf=hash("oa.effect.audit-key.v1",k.threadId().value(),k.nodeId(),k.idempotencyKey());
        String key=new AuditSource(1,sf,kf,transition,id,"0".repeat(64)).key();
        var attrs=new LinkedHashMap<String,String>();
        attrs.put("oa.chain","effect-"+hash("oa.effect.audit-chain.v1",s.tenant()));attrs.put("oa.tenant",s.tenant());attrs.put("oa.instance",s.instanceId().value());
        attrs.put("effect.owner.kind",s.owner().kind().name());attrs.put("effect.owner.id",s.owner().id());attrs.put("effect.scope",sf);attrs.put("effect.key",kf);
        attrs.put("effect.source",key);attrs.put("effect.journal",Long.toString(id));attrs.put("effect.transition",transition.name());
        attrs.put("effect.from",from==null?"NONE":from);attrs.put("effect.to",to);attrs.put("effect.purpose",value(c,"select purpose from effect.ledger where thread_name=? and node_id=? and idempotency_key=?",k.threadId().value(),k.nodeId(),k.idempotencyKey()));
        attrs.put("effect.target",hash("oa.effect.audit-target.v1",cmd.target()));attrs.put("effect.request",cmd.requestFingerprint());
        Instant at;
        try(var q=c.prepareStatement("select occurred_at from effect.event where id=?")){q.setLong(1,id);try(var r=q.executeQuery()){assertTrue(r.next());at=r.getTimestamp(1).toInstant();}}
        var draft=new AuditEvent(UlidGenerator.shared().next(),1,at,new Subject(SubjectKind.SERVICE,"system:effect-ledger"),"effect."+transition.name().toLowerCase(Locale.ROOT),"effect","efp1-"+kf,attrs,AuditHash.GENESIS,AuditHash.GENESIS);
        return new Fact(id,initial,s,cmd,new AuditSource(1,sf,kf,transition,id,AuditDraftFingerprint.of(draft)),draft);
    }
    static void source(Connection c,Fact f,String changedKey) throws Exception {
        var s=f.source();var d=f.draft();
        sql(c,"""
            insert into effect.audit_source(source_event_id,scope_initial_event_id,schema_version,scope_fingerprint,
              key_fingerprint,transition,source_sequence,source_key,draft_id,occurred_at,actor_kind,actor_id,action,
              resource_type,resource_id,attributes,content_fingerprint) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
            """,f.id(),f.initial(),1,s.scopeFingerprint(),s.keyFingerprint(),s.transition().name(),s.sequence(),changedKey==null?s.key():changedKey,
              d.id().value(),Timestamp.from(d.occurredAt()),d.actor().kind().name(),d.actor().id(),d.action(),d.resourceType(),d.resourceId(),new ObjectMapper().writeValueAsString(d.attributes()),s.contentFingerprint());
    }
    static Fact seed(Database db) throws Exception {
        var f=fixture(db,"a".repeat(64));try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{var result=initial(c,scope(f),true);c.commit();return result;}finally{c.rollback();}}
    }
    @Test void freshMigrationInstallsEmptyDataAndPinnedFunctionSearchPaths() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);db.flyway(null).validate();
            assertEquals(1,count(db.owner,"select count(*) from public.flyway_schema_history where version='77' and success"));
            for(String table:List.of("audit_scope","audit_source","audit_delivery"))assertEquals(0,count(db.owner,"select count(*) from effect."+table));
            try(var c=db.owner.getConnection()) {
                for(String fn:List.of("audit_scope_insert_guard()","audit_source_insert_guard()","audit_delivery_guard()","enqueue_audit_source()","audit_fingerprint(text,text[])"))
                    assertEquals("search_path=pg_catalog",value(c,"select array_to_string(proconfig,',') from pg_proc where oid=pg_catalog.to_regprocedure(?)","effect."+fn));
            }
        }
    }
    @Test void maliciousSessionFunctionShadowsCannotChangeThePinnedCanonicalEncoding() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try {
                sql(c,"create schema shadow;create function shadow.int4send(integer) returns bytea language sql immutable as 'select pg_catalog.decode(''00'',''hex'')';set search_path=shadow,pg_catalog,public");
                assertEquals("00",value(c,"select pg_catalog.encode(int4send(1),'hex')"),"shadow must really be active");
                String expected=hash("test-domain","value");assertEquals(expected,value(c,"select effect.audit_fingerprint('test-domain','value')"));
                initial(c,scope(f),true);
                sql(c,"alter function effect.audit_fingerprint(text,text[]) reset search_path");
                assertNotEquals(expected,value(c,"select effect.audit_fingerprint('test-domain','value')"),"removing only the pin must expose the same shadow");
            }finally{c.rollback();}}
        }
    }
    @Test void nonemptySeventySixUpgradeDoesNotInventScopesSourcesOrReceipts() throws Exception {
        try(var db=new Database()) {
            db.migrate("76");var f=fixture(db,"a".repeat(64));var cmd=scope(f).command("legacy","occurrence","approved-target","{}");
            var before=new JdbcEffectLedger(db.owner).prepare(cmd,Duration.ZERO,Instant.now());
            db.migrate(null);db.flyway(null).validate();assertEquals(before,new JdbcEffectLedger(db.owner).prepare(cmd,Duration.ZERO,Instant.now()));
            assertEquals(1,count(db.owner,"select count(*) from effect.event"));
            for(String table:List.of("audit_scope","audit_source","audit_delivery"))assertEquals(0,count(db.owner,"select count(*) from effect."+table));
        }
    }
    @Test void conflictingTableStopsMigrationWithoutRepairOrPartialFunctions() throws Exception {
        try(var db=new Database()) {
            db.migrate("76");execute(db.owner,"create table effect.audit_scope(marker text);insert into effect.audit_scope values('owned-preserve')");
            assertThrows(FlywayException.class,()->db.migrate(null));
            try(var c=db.owner.getConnection()){assertEquals("owned-preserve",value(c,"select marker from effect.audit_scope"));}
            assertEquals(0,count(db.owner,"select count(*) from public.flyway_schema_history where version='77'"));
            assertEquals(0,count(db.owner,"select count(*) from pg_proc where oid=pg_catalog.to_regprocedure('effect.audit_fingerprint(text,text[])')"));
        }
    }
    @Test void firstSourceAndPendingDeliveryCommitWithCanonicalJavaDraftIdentity() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=seed(db);
            try(var c=db.owner.getConnection()) {
                assertEquals(f.source().key(),value(c,"select source_key from effect.audit_source where source_event_id=?",f.id()));
                assertEquals(f.source().contentFingerprint(),value(c,"select content_fingerprint from effect.audit_source where source_event_id=?",f.id()));
                assertEquals(f.source().key(),value(c,"select audit.source_identity_key(1,?,?,?,?)",f.source().scopeFingerprint(),f.source().keyFingerprint(),"INTENT",f.id()));
                assertEquals("true",value(c,"select (ack_source_key is null and acked_at is null)::text from effect.audit_delivery where source_event_id=?",f.id()));
            }
            assertEquals(1,count(db.owner,"select count(*) from effect.audit_scope"));assertEquals(1,count(db.owner,"select count(*) from effect.audit_source"));assertEquals(1,count(db.owner,"select count(*) from effect.audit_delivery"));
        }
    }
    @Test void missingSourceAtCommitRollsBackTheEntireScopedIntent() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{initial(c,scope(f),false);var e=assertThrows(SQLException.class,c::commit);assertEquals("23514",e.getSQLState());}finally{c.rollback();}}
            for(String table:List.of("ledger","event","audit_scope","audit_source","audit_delivery"))assertEquals(0,count(db.owner,"select count(*) from effect."+table));
        }
    }
    @Test void queueFailureCannotLeaveAJournalOrScopeWithoutItsDelivery() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));execute(db.owner,"drop table effect.audit_delivery");
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{var e=assertThrows(SQLException.class,()->initial(c,scope(f),true));assertEquals("42P01",e.getSQLState());}finally{c.rollback();}}
            for(String table:List.of("ledger","event","audit_scope","audit_source"))assertEquals(0,count(db.owner,"select count(*) from effect."+table));
        }
    }
    @Test void fullOwnerKindCannotBeReplacedByTheSameIdentifier() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var original=scope(f);var wrong=new EffectScope(original.tenant(),original.instanceId(),new Subject(SubjectKind.SERVICE,original.owner().id()));
            assertEquals(original.threadId(),wrong.threadId(),"legacy key alone omits owner kind");
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{var e=assertThrows(SQLException.class,()->initial(c,wrong,true));assertEquals("23514",e.getSQLState());assertTrue(e.getMessage().contains("registry is not current"));}finally{c.rollback();}}
            assertEquals(0,count(db.owner,"select count(*) from effect.audit_scope"));
        }
    }
    @Test void wrongSourceKeyRefusesAndOnlyItsOwnDisabledGuardMakesTheSameProbePass() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            try(var c=db.owner.getConnection()) {
                c.setAutoCommit(false);try {
                    var fact=initial(c,scope(f),false);var before=c.setSavepoint();
                    var e=assertThrows(SQLException.class,()->source(c,fact,"as1-"+"0".repeat(64)));assertEquals("23514",e.getSQLState());assertTrue(e.getMessage().contains("source attribution differs"));
                    c.rollback(before);sql(c,"alter table effect.audit_source disable trigger audit_source_insert_guard");
                    source(c,fact,"as1-"+"0".repeat(64));assertEquals("as1-"+"0".repeat(64),value(c,"select source_key from effect.audit_source"));
                }finally{c.rollback();}
            }
            assertEquals(0,count(db.owner,"select count(*) from effect.audit_source"));
        }
    }
    @Test void onlyAnExactStoredCentralReceiptCanAcknowledgeAndItCannotBeReset() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=seed(db);
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{var e=assertThrows(SQLException.class,()->sql(c,"update effect.audit_delivery set ack_source_key=?,acked_at=clock_timestamp() where source_event_id=?",f.source().key(),f.id()));assertEquals("23514",e.getSQLState());}finally{c.rollback();}}
            var once=new AuditAppendOnce(db.owner);var receipt=once.appendOnce(f.source(),f.draft());receipt.requireMatches(f.source(),f.draft());
            try(var c=db.owner.getConnection()){sql(c,"update effect.audit_delivery set ack_source_key=?,acked_at=clock_timestamp() where source_event_id=?",receipt.source().key(),f.id());}
            assertEquals(receipt,once.appendOnce(f.source(),f.draft()));assertEquals(1,count(db.owner,"select count(*) from audit.event"));
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{var e=assertThrows(SQLException.class,()->sql(c,"update effect.audit_delivery set ack_source_key=null,acked_at=null where source_event_id=?",f.id()));assertEquals("42501",e.getSQLState());}finally{c.rollback();}}
        }
    }
    static Fact transition(Connection c,Fact previous,String from,String to,String reason,AuditSource.Transition transition) throws Exception {
        var k=previous.command().key();
        if(reason.equals("effect.dispatch"))sql(c,"update effect.ledger set attempted=true where thread_name=? and node_id=? and idempotency_key=?",k.threadId().value(),k.nodeId(),k.idempotencyKey());
        if(!from.equals(to))sql(c,"update effect.ledger set state=?,recorded_at=clock_timestamp() where thread_name=? and node_id=? and idempotency_key=?",to,k.threadId().value(),k.nodeId(),k.idempotencyKey());
        long id=Long.parseLong(value(c,"insert into effect.event(thread_name,node_id,idempotency_key,from_state,to_state,reason) values(?,?,?,?,?,?) returning id",k.threadId().value(),k.nodeId(),k.idempotencyKey(),from,to,reason));
        var result=fact(c,id,previous.initial(),previous.scope(),previous.command(),transition,from,to);source(c,result,null);return result;
    }
    @Test void finiteJournalSourcesRetainOriginalScopeAndMandatoryDelivery() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var root=seed(db);
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try {
                var dispatch=transition(c,root,"INTENDED","INTENDED","effect.dispatch",AuditSource.Transition.DISPATCH);
                var unknown=transition(c,dispatch,"INTENDED","INTENDED","effect.outcome.unknown",AuditSource.Transition.UNKNOWN);
                var failed=transition(c,unknown,"INTENDED","FAILED","effect.outcome",AuditSource.Transition.OUTCOME);
                var unresolved=transition(c,failed,"FAILED","FAILED","effect.compensation.unresolved",AuditSource.Transition.UNKNOWN);
                transition(c,unresolved,"FAILED","COMPENSATED","effect.outcome",AuditSource.Transition.COMPENSATION);c.commit();
            }finally{c.rollback();}}
            for(String table:List.of("event","audit_source","audit_delivery"))assertEquals(6,count(db.owner,"select count(*) from effect."+table));
            assertEquals(1,count(db.owner,"select count(distinct scope_initial_event_id) from effect.audit_source"));
            try(var c=db.owner.getConnection()){var e=assertThrows(SQLException.class,()->value(c,"select effect.audit_transition('effect.dispatch','FAILED','INTENDED')"));assertEquals("23514",e.getSQLState());}
        }
    }
    @Test void compensationKeepsOriginalProvenanceAfterRegistryEndWithoutInheritingPermission() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var root=seed(db);
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try {
                var dispatch=transition(c,root,"INTENDED","INTENDED","effect.dispatch",AuditSource.Transition.DISPATCH);
                transition(c,dispatch,"INTENDED","CONFIRMED","effect.outcome",AuditSource.Transition.OUTCOME);c.commit();
            }finally{c.rollback();}}
            execute(db.owner,"update task.instance set status='COMPLETED' where id='"+root.scope().instanceId()+"'");
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try {
                var cmd=root.scope().command("refund","occurrence-undo","approved-refund","{}");
                var undo=initial(c,root.scope(),cmd,root.initial(),"COMPENSATION",true);var k=root.command().key();var u=undo.command().key();
                sql(c,"insert into effect.compensation(thread_name,node_id,idempotency_key,compensation_node,compensation_key) values(?,?,?,?,?)",k.threadId().value(),k.nodeId(),k.idempotencyKey(),u.nodeId(),u.idempotencyKey());c.commit();
            }finally{c.rollback();}}
            assertEquals(2,count(db.owner,"select count(*) from effect.audit_scope"));assertEquals(1,count(db.owner,"select count(*) from effect.audit_scope where parent_initial_event_id is not null"));
            assertEquals(4,count(db.owner,"select count(*) from effect.audit_delivery where ack_source_key is null"));
        }
    }
    @Test void anAcknowledgmentCannotOvertakeAnEarlierPendingSource() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var first=seed(db);Fact second;
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{second=transition(c,first,"INTENDED","INTENDED","effect.dispatch",AuditSource.Transition.DISPATCH);c.commit();}finally{c.rollback();}}
            var once=new AuditAppendOnce(db.owner);
            // Central receipt DATA does not itself prove causal delivery. The future relay must check BEFORE append.
            var later=once.appendOnce(second.source(),second.draft());
            try(var c=db.owner.getConnection()){c.setAutoCommit(false);try{var e=assertThrows(SQLException.class,()->sql(c,"update effect.audit_delivery set ack_source_key=?,acked_at=clock_timestamp() where source_event_id=?",later.source().key(),second.id()));assertEquals("23514",e.getSQLState());assertTrue(e.getMessage().contains("earlier effect audit fact remains pending"));}finally{c.rollback();}}
            var earlier=once.appendOnce(first.source(),first.draft());
            try(var c=db.owner.getConnection()){sql(c,"update effect.audit_delivery set ack_source_key=?,acked_at=clock_timestamp() where source_event_id=?",earlier.source().key(),first.id());sql(c,"update effect.audit_delivery set ack_source_key=?,acked_at=clock_timestamp() where source_event_id=?",later.source().key(),second.id());}
            assertEquals(2,count(db.owner,"select count(*) from effect.audit_delivery where ack_source_key is not null"));
        }
    }
    @TestFactory Stream<DynamicTest> aRegistryChangeOrExpiryWhileItsRealRowLockBlocksTheWriterRefusesAfterWait() {
        return Stream.of("cancel","expiry").map(change->DynamicTest.dynamicTest(change,()->{
            try(var db=new Database()) {
                db.migrate(null);var f=fixture(db,"a".repeat(64));var pool=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var started=new CountDownLatch(1);
                try(var blocker=db.owner.getConnection()) {
                    blocker.setAutoCommit(false);value(blocker,"select id from task.instance where id=? for update",f.row().id().value());
                    if(change.equals("expiry"))sql(blocker,"update task.instance set status='WAITING',wait_deadline=clock_timestamp()+interval '300 milliseconds' where id=?",f.row().id().value());
                    var result=pool.submit(()->{try(var c=db.owner.getConnection()){c.setAutoCommit(false);pid.set(Integer.parseInt(value(c,"select pg_backend_pid()")));started.countDown();try{initial(c,scope(f),true);c.commit();return "unexpected success";}catch(SQLException e){return e.getSQLState();}finally{c.rollback();}}});
                    assertTrue(started.await(5,TimeUnit.SECONDS));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                    try(var observer=db.owner.getConnection()) {
                        while(System.nanoTime()<until){if(value(observer,"select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')::text",pid.get()).equals("true")){blocked=true;break;}Thread.sleep(10);}
                    }
                    assertTrue(blocked,"writer must actually wait on PostgreSQL row lock");
                    if(change.equals("cancel"))sql(blocker,"update task.instance set status='CANCEL_REQUESTED' where id=?",f.row().id().value());
                    else {while(value(blocker,"select (clock_timestamp()>wait_deadline)::text from task.instance where id=?",f.row().id().value()).equals("false"))Thread.sleep(10);}
                    blocker.commit();assertEquals("23514",result.get(5,TimeUnit.SECONDS));
                }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
                assertEquals(0,count(db.owner,"select count(*) from effect.ledger"));assertEquals(0,count(db.owner,"select count(*) from effect.audit_source"));
            }
        }));
    }
    @TestFactory Stream<DynamicTest> retainedScopeSourceAndDeliveryCannotBeDeletedOrTruncated() {
        return Stream.of("audit_scope","audit_source","audit_delivery").flatMap(table ->
                Stream.of("delete from effect."+table,"truncate effect."+table+" cascade").map(probe ->
                        DynamicTest.dynamicTest(probe,()->{
                            try(var db=new Database()) {
                                db.migrate(null);seed(db);
                                try(var c=db.owner.getConnection()) {
                                    c.setAutoCommit(false);
                                    try {var e=assertThrows(SQLException.class,()->sql(c,probe));assertEquals("42501",e.getSQLState());}
                                    finally {c.rollback();}
                                }
                                assertEquals(1,count(db.owner,"select count(*) from effect."+table));
                            }
                        })));
    }
}
