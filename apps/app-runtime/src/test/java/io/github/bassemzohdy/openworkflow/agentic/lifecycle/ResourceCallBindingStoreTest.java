package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Actual PostgreSQL metadata storage. Not JWT verification, dispatch, graph execution or workflow401 proof. */
@QuarkusTest
class ResourceCallBindingStoreTest {
    @Inject DataSource data;
    @Inject InstanceStore registry;
    @Inject DelegationGrants grants;
    @Inject JdbcResourceCallBindings bindings;
    @Inject UserTransaction transaction;
    @Inject jakarta.transaction.TransactionManager transactionManager;
    private final List<InstanceRecord> instances=new ArrayList<>();
    private static final Subject ALICE=new Subject(SubjectKind.USER,"alice");
    private static final Subject WORKFLOW=new Subject(SubjectKind.WORKFLOW,"binding-test");
    record Fixture(InstanceRecord row,ResourceCallBinding binding) { }
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    Fixture fixture() {
        var request=new StartRequest("call-"+UUID.randomUUID(),ALICE,new DefinitionRef("agentic/binding-store-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        instances.add(row);
        row=registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",ALICE);
        var scope=new ResourceCallScope(row.tenant(),row.id(),ALICE,"app",row.engineInstanceId(),"agentic","binding-store-test","1",
                "do/0/invoke",1,"root/foreach/1","agent-run","run","a".repeat(64),"b".repeat(64));
        var grant=grants.grant(row.tenant(),ALICE,ALICE,WORKFLOW,Set.of("oa.agent.run"),now().plusSeconds(3600),null);
        return new Fixture(row,credential(scope,grant.id(),"https://idp.test",UUID.randomUUID().toString(),now().plusSeconds(120)));
    }
    ResourceCallBinding credential(ResourceCallScope scope,Ulid grant,String issuer,String jti,Instant expires) {
        new JdbcIssuedTokenBindings(data).bind(issuer,jti,new IssuedTokenBindings.Binding(grant,scope.tenant(),expires));
        return new ResourceCallBinding(issuer,jti,grant,scope,expires.minusSeconds(120),expires);
    }
    ResourceCallBinding fresh(ResourceCallBinding b,ResourceCallScope scope) {
        return credential(scope,b.issuingGrantId(),b.issuer(),UUID.randomUUID().toString(),b.expiresAt());
    }
    static ResourceCallBinding sameToken(ResourceCallBinding b,ResourceCallScope scope) {
        return new ResourceCallBinding(b.issuer(),b.tokenId(),b.issuingGrantId(),scope,b.issuedAt(),b.expiresAt());
    }
    static ResourceCallScope change(ResourceCallScope s,String field,String value) {
        return new ResourceCallScope(field.equals("tenant")?value:s.tenant(),s.instanceId(),
                field.equals("kind")?new Subject(SubjectKind.valueOf(value),s.owner().id()):
                field.equals("owner")?new Subject(s.owner().kind(),value):s.owner(),s.applicationId(),
                field.equals("engine")?value:s.engineInstanceId(),s.namespace(),
                field.equals("definition")?value:s.definition(),field.equals("version")?value:s.version(),
                s.taskPosition(),field.equals("iteration")?Integer.parseInt(value):s.iteration(),field.equals("occurrence")?value:s.logicalOccurrence(),
                field.equals("audience")?value:s.audience(),field.equals("operation")?value:s.operation(),
                field.equals("target")?value:s.targetFingerprint(),field.equals("request")?value:s.requestFingerprint());
    }
    @AfterEach void finishTestRegistryRows() {
        for(var original:instances) {
            var row=registry.find(original.tenant(),original.id()).orElseThrow();
            if(!row.status().isTerminal()) registry.replace(row.successor(now(),InstanceStatus.COMPLETED,row.engineInstanceId(),
                    row.startAttempts(),null,null),row.version(),"test.complete",ALICE);
        }
    }
    long count(Fixture f,String table) throws SQLException {
        String sql=table.equals("task.resource_call")?"select count(*) from task.resource_call where instance_id=?":
                "select count(*) from task.resource_call_binding b join task.resource_call c on c.call_key=b.call_key where c.instance_id=?";
        try(var c=data.getConnection();var s=c.prepareStatement(sql)) {
            s.setString(1,f.row.id().value());try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    @Test void exactRepeatsNewCredentialsAndSameJtiDifferentIssuersRetainOneCall() throws Exception {
        var f=fixture();var b=f.binding;
        assertEquals(b,bindings.bind(b));assertEquals(b,bindings.bind(b));
        assertEquals(b,new JdbcResourceCallBindings(data).find(b.issuer(),b.tokenId()).orElseThrow());
        var other=fresh(b,b.scope());assertEquals(other,bindings.bind(other));
        var issuer=credential(b.scope(),b.issuingGrantId(),"https://other-idp.test",b.tokenId(),b.expiresAt());
        assertEquals(issuer,bindings.bind(issuer));
        assertEquals(1,count(f,"task.resource_call"));assertEquals(3,count(f,"task.resource_call_binding"));
        assertEquals(b.scope().threadId(),other.scope().threadId());
    }
    @Test void concurrentExactBindReturnsTheOneImmutableRecord() throws Exception {
        var f=fixture();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);
        try {
            List<Future<ResourceCallBinding>> results=new ArrayList<>();
            for(int i=0;i<6;i++) results.add(pool.submit(()->{start.await();return new JdbcResourceCallBindings(data).bind(f.binding);}));
            start.countDown();for(var result:results) assertEquals(f.binding,result.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,count(f,"task.resource_call"));assertEquals(1,count(f,"task.resource_call_binding"));
    }
    @Test void freshTokenCannotRetargetOrChangeRequestForTheSameInvocation() throws Exception {
        var f=fixture();bindings.bind(f.binding);
        for(String field:List.of("target","request","audience","operation")) {
            var scope=change(f.binding.scope(),field,field.equals("target")||field.equals("request")?"c".repeat(64):"other");
            assertEquals(f.binding.scope().key(),scope.key());
            var candidate=fresh(f.binding,scope);
            assertThrows(IllegalStateException.class,()->bindings.bind(candidate),field);
            assertTrue(bindings.find(candidate.issuer(),candidate.tokenId()).isEmpty());
        }
        assertEquals(1,count(f,"task.resource_call"));assertEquals(1,count(f,"task.resource_call_binding"));
    }
    @Test void issuerJtiCannotMoveToAnotherInvocationAndLeavesNoOrphan() throws Exception {
        var f=fixture();bindings.bind(f.binding);
        var candidate=sameToken(f.binding,change(f.binding.scope(),"occurrence","root/foreach/2"));
        assertThrows(IllegalStateException.class,()->bindings.bind(candidate));
        assertEquals(1,count(f,"task.resource_call"));assertEquals(1,count(f,"task.resource_call_binding"));
    }
    @Test void competingContractsHaveExactlyOneWinnerAndNoOrphanCredentialMapping() throws Exception {
        var f=fixture();var other=fresh(f.binding,change(f.binding.scope(),"request","c".repeat(64)));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(var candidate:List.of(f.binding,other)) results.add(pool.submit(()->{
                start.await();try {new JdbcResourceCallBindings(data).bind(candidate);return true;}
                catch(IllegalStateException conflict) {return false;}
            }));
            start.countDown();assertNotEquals(results.get(0).get(15,TimeUnit.SECONDS),results.get(1).get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertNotEquals(bindings.find(f.binding.issuer(),f.binding.tokenId()).isPresent(),bindings.find(other.issuer(),other.tokenId()).isPresent());
        assertEquals(1,count(f,"task.resource_call"));assertEquals(1,count(f,"task.resource_call_binding"));
    }
    @Test void registryTenantFullOwnerDefinitionVersionAndEngineMustMatch() throws Exception {
        var f=fixture();
        for(var entry:Map.of("tenant","foreign","kind","SERVICE","owner","bob","definition","other","version","2","engine","foreign").entrySet()) {
            var candidate=sameToken(f.binding,change(f.binding.scope(),entry.getKey(),entry.getValue()));
            assertThrows(IllegalStateException.class,()->bindings.bind(candidate),entry.getKey());
        }
        assertEquals(0,count(f,"task.resource_call"));assertEquals(0,count(f,"task.resource_call_binding"));
    }
    @Test void exactV65GrantTenantExpiryAndGrantSubjectAreRequiredWithAtomicRollback() throws Exception {
        var f=fixture();var b=f.binding;
        var other=grants.grant(f.row.tenant(),ALICE,ALICE,WORKFLOW,Set.of("oa.agent.run"),now().plusSeconds(3600),null);
        var wrongGrant=new ResourceCallBinding(b.issuer(),b.tokenId(),other.id(),b.scope(),b.issuedAt(),b.expiresAt());
        var wrongExpiry=new ResourceCallBinding(b.issuer(),b.tokenId(),b.issuingGrantId(),b.scope(),b.issuedAt(),b.expiresAt().plusSeconds(1));
        var missing=new ResourceCallBinding(b.issuer(),UUID.randomUUID().toString(),b.issuingGrantId(),b.scope(),b.issuedAt(),b.expiresAt());
        for(var candidate:List.of(wrongGrant,wrongExpiry,missing)) assertThrows(StoreUnavailableException.class,()->bindings.bind(candidate));
        Subject bob=new Subject(SubjectKind.USER,"bob");
        var bobGrant=grants.grant(f.row.tenant(),bob,bob,WORKFLOW,Set.of("oa.agent.run"),now().plusSeconds(3600),null);
        var wrongOwner=credential(b.scope(),bobGrant.id(),b.issuer(),UUID.randomUUID().toString(),b.expiresAt());
        assertThrows(StoreUnavailableException.class,()->bindings.bind(wrongOwner));
        var foreign=grants.grant("foreign-"+UUID.randomUUID(),ALICE,ALICE,WORKFLOW,Set.of("oa.agent.run"),now().plusSeconds(3600),null);
        String foreignJti=UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(data).bind(b.issuer(),foreignJti,new IssuedTokenBindings.Binding(foreign.id(),foreign.tenant(),b.expiresAt()));
        var wrongTenant=new ResourceCallBinding(b.issuer(),foreignJti,foreign.id(),b.scope(),b.issuedAt(),b.expiresAt());
        assertThrows(StoreUnavailableException.class,()->bindings.bind(wrongTenant));
        assertEquals(0,count(f,"task.resource_call"));assertEquals(0,count(f,"task.resource_call_binding"));
    }
    @Test void terminalRegistryAllowsOnlyHistoricalReadbackNotNewBinding() throws Exception {
        var f=fixture();bindings.bind(f.binding);
        var row=registry.find(f.row.tenant(),f.row.id()).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.COMPLETED,row.engineInstanceId(),row.startAttempts(),null,null),row.version(),"test.complete",ALICE);
        assertEquals(f.binding,bindings.bind(f.binding));
        assertEquals(f.binding,bindings.find(f.binding.issuer(),f.binding.tokenId()).orElseThrow());
        var fresh=fresh(f.binding,f.binding.scope());
        assertThrows(IllegalStateException.class,()->bindings.bind(fresh));
        assertEquals(1,count(f,"task.resource_call_binding"));
    }
    @Test void unknownActualCommitReplyRefusesReturnAndSameIdentityReadbackReconciles() throws Exception {
        var f=fixture();AtomicBoolean lose=new AtomicBoolean(true);
        var wrapped=proxy(DataSource.class,data,(connection)->proxy(Connection.class,connection,null,lose),null);
        var uncertain=new JdbcResourceCallBindings(wrapped);
        assertThrows(StoreUnavailableException.class,()->uncertain.bind(f.binding));
        assertEquals(f.binding,bindings.find(f.binding.issuer(),f.binding.tokenId()).orElseThrow());
        assertEquals(f.binding,uncertain.bind(f.binding));
        assertEquals(1,count(f,"task.resource_call"));assertEquals(1,count(f,"task.resource_call_binding"));
    }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type,T target,UnaryOperator<Connection> wrap,AtomicBoolean lose) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            Object result;
            try {result=m.invoke(target,a);}catch(InvocationTargetException e){throw e.getCause();}
            if(wrap!=null && result instanceof Connection c) return wrap.apply(c);
            if(lose!=null && m.getName().equals("commit") && lose.compareAndSet(true,false)) throw new SQLException("actual commit reply lost");
            return result;
        });
    }
    @Test void callerRollbackCannotEraseAlreadyCommittedBinding() throws Exception {
        var f=fixture();
        transaction.begin();
        try {assertEquals(f.binding,bindings.bind(f.binding));assertEquals(jakarta.transaction.Status.STATUS_ACTIVE,transaction.getStatus());}
        finally {transaction.rollback();}
        assertEquals(f.binding,new JdbcResourceCallBindings(data).find(f.binding.issuer(),f.binding.tokenId()).orElseThrow());
    }
    @Test void executionEntryCommitsOutsideCallerTransactionAndResumesItBeforeCallback() throws Exception {
        var f=fixture();Instant serverNow;
        try(var c=data.getConnection();var q=c.createStatement();var r=q.executeQuery("select pg_catalog.clock_timestamp()")) {
            assertTrue(r.next());serverNow=r.getTimestamp(1).toInstant();
        }
        // This positive fixture must not depend on the Windows/JVM clock leading PostgreSQL. Production still
        // refuses future-issued metadata; derive a safely past issue time from the SAME server used by V76.
        var b=credential(f.binding.scope(),f.binding.issuingGrantId(),f.binding.issuer(),UUID.randomUUID().toString(),serverNow.plusSeconds(119));
        bindings.bind(b);
        var entry=new io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry(b.scope(),1);
        try(var gate=new JdbcResourceExecutionGate(data,transactionManager,1)) {
            transaction.begin();
            try {
                var result=gate.open(b,entry,UUID.randomUUID(),ignored->{ }).execute(ignored->{
                    assertDoesNotThrow(()->assertEquals(jakarta.transaction.Status.STATUS_ACTIVE,transactionManager.getStatus()));
                    return "synchronous-fixture-callback";
                });
                assertEquals(JdbcResourceExecutionGate.Kind.CALLED,result.kind());
                assertEquals(jakarta.transaction.Status.STATUS_ACTIVE,transaction.getStatus());
            } finally {transaction.rollback();}
            assertEquals(entry,gate.find(b.scope()).orElseThrow().entry());
        }
    }
    @Test void directSqlCannotBypassRegistryOrExactIssuingMetadataChecks() throws Exception {
        var f=fixture();var b=f.binding;bindings.bind(b);
        try(var c=data.getConnection();var s=c.prepareStatement("""
                insert into task.resource_call
                select 'rc1-'||repeat('c',64),fingerprint,tenant,instance_id,'SERVICE',owner_id,application_id,
                engine_instance_id,namespace,definition_name,definition_version,task_position,iteration,
                logical_occurrence,audience,operation,target_fingerprint,request_fingerprint,created_at
                from task.resource_call where call_key=?
                """)) {
            s.setString(1,b.scope().key());assertEquals("23514",assertThrows(SQLException.class,s::executeUpdate).getSQLState());
        }
        var next=fresh(b,b.scope());
        try(var c=data.getConnection();var s=c.prepareStatement("""
                insert into task.resource_call_binding(binding_key,issuer,token_id,call_key,grant_id,issued_at,expires_at)
                values(?,?,?,?,?,?,?)
                """)) {
            s.setString(1,next.key());s.setString(2,next.issuer());s.setString(3,next.tokenId());s.setString(4,next.scope().key());
            s.setString(5,next.issuingGrantId().value());s.setTimestamp(6,Timestamp.from(next.issuedAt()));
            s.setTimestamp(7,Timestamp.from(next.expiresAt().plusSeconds(1)));
            assertEquals("23514",assertThrows(SQLException.class,s::executeUpdate).getSQLState());
        }
        assertEquals(1,count(f,"task.resource_call"));assertEquals(1,count(f,"task.resource_call_binding"));
    }
    @Test void immutableRowsAndIssuingReferencesAreRetainedPastSignedExpiry() throws Exception {
        var f=fixture();var b=credential(f.binding.scope(),f.binding.issuingGrantId(),f.binding.issuer(),UUID.randomUUID().toString(),now().minusSeconds(180));
        bindings.bind(b);assertEquals(b,bindings.bind(b)); // historical metadata, never dispatch permission
        for(String sql:List.of("update task.resource_call set request_fingerprint=request_fingerprint where call_key='"+b.scope().key()+"'",
                "delete from task.resource_call where call_key='"+b.scope().key()+"'",
                "update task.resource_call_binding set token_id=token_id where binding_key='"+b.key()+"'",
                "delete from task.resource_call_binding where binding_key='"+b.key()+"'",
                "truncate task.resource_call_binding","truncate task.resource_call,task.resource_call_binding")) {
            try(var c=data.getConnection()) {c.setAutoCommit(false);try(var s=c.createStatement()) {assertThrows(SQLException.class,()->s.execute(sql));}finally{c.rollback();}}
        }
        try(var c=data.getConnection();var s=c.prepareStatement("delete from identity.issued_token_binding where issuer=? and token_id=?")) {
            s.setString(1,b.issuer());s.setString(2,b.tokenId());
            assertEquals("23503",assertThrows(SQLException.class,s::executeUpdate).getSQLState(),"V73 FK retains metadata beyond V65 one-minute rule");
        }
        assertEquals(b,bindings.find(b.issuer(),b.tokenId()).orElseThrow());
    }

    @Test void canonicalScopeSurvivesChangedEntryCounterAndRefreshButDistinctBusinessWorkIsSeparate() throws Exception {
        var f=fixture();var b=f.binding;
        assertTrue(bindings.findInvocation(b.scope()).isEmpty());bindings.bind(b);
        var observed=change(b.scope(),"iteration","2");assertNotEquals(b.scope().key(),observed.key());
        var canonical=new JdbcResourceCallBindings(data).findInvocation(observed).orElseThrow();assertEquals(b.scope(),canonical);
        var credential=fresh(b,observed);
        assertThrows(IllegalStateException.class,()->bindings.bind(credential));
        var recovered=sameToken(credential,canonical);assertEquals(recovered,bindings.bind(recovered));
        assertEquals(b.scope().threadId(),recovered.scope().threadId());
        var nextScope=change(observed,"occurrence","root/foreach/2");
        assertTrue(bindings.findInvocation(nextScope).isEmpty());bindings.bind(fresh(b,nextScope));
        assertEquals(2,count(f,"task.resource_call"));assertEquals(3,count(f,"task.resource_call_binding"));
        var row=registry.find(f.row.tenant(),f.row.id()).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.COMPLETED,row.engineInstanceId(),row.startAttempts(),null,null),row.version(),"test.complete",ALICE);
        assertEquals(b.scope(),bindings.findInvocation(observed).orElseThrow(),"historical lookup does not refresh authority");
    }
    @Test void occurrenceLookupRefusesChangedTargetRequestAudienceAndOperation() {
        var f=fixture();var b=f.binding;bindings.bind(b);
        for(String field:List.of("target","request","audience","operation")) {
            var candidate=change(change(b.scope(),"iteration","9"),field,field.equals("target")||field.equals("request")?"c".repeat(64):"other");
            assertThrows(IllegalStateException.class,()->bindings.findInvocation(candidate),field);
        }
    }
    void copyCallWithNewCounter(Connection c,String schema,ResourceCallScope first,ResourceCallScope second) throws SQLException {
        // schema names are generated test-owned lowercase/hex, never input or a public API.
        if(!schema.matches("(?:task|gpt_occ_[a-f0-9]{32})")) throw new IllegalArgumentException("test schema");
        try(var s=c.prepareStatement("insert into "+schema+".resource_call "+"""
                select ?,?,tenant,instance_id,owner_kind,owner_id,application_id,engine_instance_id,namespace,
                definition_name,definition_version,task_position,?,logical_occurrence,audience,operation,
                target_fingerprint,request_fingerprint,created_at
                from
                """+schema+".resource_call where call_key=?")) {
            s.setString(1,second.key());s.setString(2,second.fingerprint());s.setInt(3,second.iteration());s.setString(4,first.key());s.executeUpdate();
        }
    }
    @Test void directSqlCannotCreateAnotherCallForTheSameOccurrenceOrRewriteItsAnchor() throws Exception {
        var f=fixture();var b=f.binding;bindings.bind(b);
        try(var c=data.getConnection()) {
            assertEquals("23505",assertThrows(SQLException.class,()->copyCallWithNewCounter(c,"task",b.scope(),change(b.scope(),"iteration","2"))).getSQLState());
        }
        try(var c=data.getConnection();var s=c.prepareStatement("insert into task.resource_occurrence(occurrence_key,call_key) values(?,?)")) {
            s.setString(1,"ro1-"+"f".repeat(64));s.setString(2,b.scope().key());
            assertEquals("23514",assertThrows(SQLException.class,s::executeUpdate).getSQLState(),"derive the occurrence from full retained fields before uniqueness checks");
        }
        for(String sql:List.of("update task.resource_occurrence set call_key=call_key where occurrence_key=?",
                "delete from task.resource_occurrence where occurrence_key=?")) {
            try(var c=data.getConnection();var s=c.prepareStatement(sql)) {
                s.setString(1,b.scope().occurrenceKey());assertEquals("42501",assertThrows(SQLException.class,s::executeUpdate).getSQLState());
            }
        }
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                // V76 references the occurrence; cascade reaches the intended parent guard before its descendants.
                var refused=assertThrows(SQLException.class,()->s.execute("truncate task.resource_occurrence cascade"));
                assertEquals("42501",refused.getSQLState());
                assertTrue(refused.getMessage().contains("resource occurrence is immutable and retained"),refused.getMessage());
            }
            finally {c.rollback();}
        }
        assertEquals(1,count(f,"task.resource_call"));assertEquals(b.scope(),bindings.findInvocation(b.scope()).orElseThrow());
    }
    String occurrenceMigration(String schema) throws Exception {
        if(!schema.matches("gpt_occ_[a-f0-9]{32}")) throw new IllegalArgumentException("test schema");
        try(var input=getClass().getClassLoader().getResourceAsStream("db/migration/V74__canonical_resource_occurrence.sql")) {
            assertNotNull(input);return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("task.",schema+".");
        }
    }
    void clonePreOccurrenceCall(Connection c,String schema,ResourceCallScope original) throws SQLException {
        if(!schema.matches("gpt_occ_[a-f0-9]{32}")) throw new IllegalArgumentException("test schema");
        try(var s=c.createStatement()) {
            s.execute("create schema "+schema);
            s.execute("create table "+schema+".resource_call (like task.resource_call including all)");
        }
        try(var s=c.prepareStatement("insert into "+schema+".resource_call select * from task.resource_call where call_key=?")) {
            s.setString(1,original.key());assertEquals(1,s.executeUpdate());
        }
    }
    @Test void actualAdditiveMigrationBackfillsExactUnicodeCanonicalLookupWithoutChangingOldKey() throws Exception {
        var f=fixture();var scope=change(f.binding.scope(),"occurrence","root/é|二/0");bindings.bind(fresh(f.binding,scope));
        String schema="gpt_occ_"+UUID.randomUUID().toString().replace("-","");
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try {
                clonePreOccurrenceCall(c,schema,scope);
                try(var s=c.createStatement()){s.execute(occurrenceMigration(schema));}
                try(var s=c.createStatement();var r=s.executeQuery("select occurrence_key,call_key from "+schema+".resource_occurrence")) {
                    assertTrue(r.next());assertEquals(scope.occurrenceKey(),r.getString(1));assertEquals(scope.key(),r.getString(2));assertFalse(r.next());
                }
            } finally {c.rollback();} // Includes all temporary DDL: never drop/alter another fixture's schema.
        }
        assertEquals(scope,bindings.findInvocation(scope).orElseThrow());
    }
    @Test void actualAdditiveMigrationRefusesConflictingHistoricalOccurrencesAndRetainsBothOriginalRows() throws Exception {
        var f=fixture();var scope=f.binding.scope();bindings.bind(f.binding);
        String schema="gpt_occ_"+UUID.randomUUID().toString().replace("-","");
        try(var c=data.getConnection()) {
            c.setAutoCommit(false);
            try {
                clonePreOccurrenceCall(c,schema,scope);copyCallWithNewCounter(c,schema,scope,change(scope,"iteration","2"));
                var beforeMigration=c.setSavepoint();
                try(var s=c.createStatement()) {assertEquals("23505",assertThrows(SQLException.class,()->s.execute(occurrenceMigration(schema))).getSQLState());}
                c.rollback(beforeMigration);
                try(var s=c.createStatement();var r=s.executeQuery("select count(*) from "+schema+".resource_call")) {assertTrue(r.next());assertEquals(2,r.getLong(1));}
                try(var s=c.prepareStatement("select to_regclass(?)")) {
                    s.setString(1,schema+".resource_occurrence");try(var r=s.executeQuery()){assertTrue(r.next());assertNull(r.getString(1));}
                }
            } finally {c.rollback();}
        }
        assertEquals(1,count(f,"task.resource_call"));assertEquals(scope,bindings.findInvocation(scope).orElseThrow());
    }
}
