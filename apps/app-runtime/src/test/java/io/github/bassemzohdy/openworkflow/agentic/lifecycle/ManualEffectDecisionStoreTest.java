package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.time.*;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Actual opt-in resolver against private PostgreSQL; V80 remains unapplied by Flyway/default runtime wiring. */
@io.quarkus.test.junit.QuarkusTest
class ManualEffectDecisionStoreTest {
    @Inject TransactionManager manager;
    @Inject jakarta.transaction.TransactionSynchronizationRegistry transactionRegistry;
    ResourceTaskAllocationMigrationTest support;
    EffectAuditProducer producer;
    JdbcEffectLedger ledger;
    @BeforeEach void setup() throws Exception {
        support=new ResourceTaskAllocationMigrationTest();support.create();support.apply();ManualEffectDecisionMigrationTest.apply(support.db.owner);
        producer=new EffectAuditProducer();ledger=new JdbcEffectLedger(support.db.owner,manager,producer);
    }
    @AfterEach void cleanup() throws Exception {if(support!=null)support.close();}

    private Ready unresolved(String occurrence) {
        var owner=new Subject(SubjectKind.USER,"manual-owner");var registry=new JdbcInstanceStore(support.db.owner);
        var request=new StartRequest("tenant-manual",owner,new DefinitionRef("agentic/manual-fixture","1"),Map.of(),"manual-"+UUID.randomUUID());
        var started=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var instance=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,started),"test.start").record();
        registry.replace(instance.successor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),InstanceStatus.RUNNING,
                "engine-"+instance.id(),1,null,null),instance.version(),"test.running",owner);
        var scope=new EffectScope(instance.tenant(),instance.id(),owner);var command=scope.command("charge",occurrence,"approved-fixture","{}");
        ledger.prepareScoped(scope,command,Duration.ZERO,Instant.now());ledger.markAttempted(command.key());ledger.requireManualResolution(command.key(),"effect.outcome.unknown");
        return new Ready(owner,instance,command);
    }
    private CallerAuthority authority(Ready f,Clock clock,Instant expires) {
        var now=clock.instant();var token=new VerifiedToken("test-issuer",f.owner(),Set.of("test-audience"),Set.of("oa.recover"),
                f.instance().tenant(),now,expires,"manual-token",List.of());return CallerAuthority.ofUserToken(token,clock);
    }
    private Pep allowingPep(){var policy=new PolicyRef("test.resolve-effect","1");return new Pep(request->{
        if(request.action()!=ActionKind.OPERATION||!Pep.InstanceOperation.RESOLVE_EFFECT.resource().equals(request.resource())
                ||request.subject().kind()!=SubjectKind.USER||!request.context().scopes().contains("oa.recover"))
            return new Decision("test-engine",DecisionOutcome.DENY,"test policy refused unrelated operation",policy,Instant.now());
        return new Decision("test-engine",DecisionOutcome.ALLOW,"allowed",policy,Instant.now());
    },event->{});
    }
    private ManualEffectResolver resolver(Pep pep,AuditAppendOnce audit){return new ManualEffectResolver(support.db.owner,manager,pep,audit,ledger);}
    private long count(String sql)throws Exception{return ResourceExecutionMigrationTest.count(support.db.owner,sql);}
    private record Ready(Subject owner,InstanceRecord instance,EffectCommand command){}
    private DataSource observingSuspendedConnections(AtomicInteger opened,int failAt) {
        return (DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getConnection")) {
                        assertNull(manager.getTransaction(),"owned JDBC work must suspend the caller transaction");
                        if(opened.incrementAndGet()==failAt)throw new SQLException("injected connection failure");
                    }
                    try{return method.invoke(support.db.owner,args);}
                    catch(InvocationTargetException failed){throw failed.getCause();}
                });
    }
    private DataSource asRole(String role) {
        if(!role.matches("manual_probe_[0-9a-f]{32}"))throw new IllegalArgumentException("invalid test role");
        return (DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getConnection")&&method.getParameterCount()==0) {
                        var connection=support.db.owner.getConnection();
                        try(var statement=connection.createStatement()){statement.execute("set role "+role);return connection;}
                        catch(Throwable failure){connection.close();throw failure;}
                    }
                    try{return method.invoke(support.db.owner,args);}catch(InvocationTargetException failed){throw failed.getCause();}
                });
    }
    private DataSource lostAuditReplyAndUnavailableReadback(AtomicInteger opened) {
        return (DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getConnection")) {
                        assertNull(manager.getTransaction(),"audit append and readback must suspend the caller transaction");
                        if(opened.incrementAndGet()>1)throw new SQLException("injected readback failure");
                        var connection=support.db.owner.getConnection();
                        return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class[]{java.sql.Connection.class},
                                (p,m,a)->{
                                    if(m.getName().equals("commit")){connection.commit();throw new SQLException("lost commit reply","08006");}
                                    try{return m.invoke(connection,a);}catch(InvocationTargetException failed){throw failed.getCause();}
                                });
                    }
                    try{return method.invoke(support.db.owner,args);}catch(InvocationTargetException failed){throw failed.getCause();}
                });
    }
    private DataSource withTransactionIsolation(DataSource delegate,int isolation) {
        return (DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},
                (proxy,method,args)->{
                    if(!method.getName().equals("getConnection")||method.getParameterCount()!=0)try{return method.invoke(delegate,args);}
                    catch(InvocationTargetException failed){throw failed.getCause();}
                    var connection=delegate.getConnection();int previous=connection.getTransactionIsolation();
                    try{connection.setTransactionIsolation(isolation);}catch(SQLException failed){connection.close();throw failed;}
                    return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class[]{java.sql.Connection.class},
                            (connectionProxy,connectionMethod,connectionArgs)->{
                                if(connectionMethod.getName().equals("close")&&connectionMethod.getParameterCount()==0) {
                                    SQLException reset=null;try{connection.setTransactionIsolation(previous);}catch(SQLException failed){reset=failed;}
                                    try{connection.close();}catch(SQLException failed){if(reset!=null)failed.addSuppressed(reset);throw failed;}
                                    if(reset!=null)throw reset;return null;
                                }
                                try{return connectionMethod.invoke(connection,connectionArgs);}
                                catch(InvocationTargetException failed){throw failed.getCause();}
                            });
                });
    }

    @Test void centralManualDecisionPrecedesAtomicOutcomeAndRetryReusesTheSameEvent() throws Exception {
        var fixture=unresolved("occur-1");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var resolver=resolver(allowingPep(),new AuditAppendOnce(support.db.owner));
        var requestId=UlidGenerator.shared().next();
        var resolved=resolver.resolve(authority,fixture.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED");
        assertEquals(EffectState.CONFIRMED,resolved.state());
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
        var registry=new JdbcInstanceStore(support.db.owner);var current=registry.find(fixture.instance().tenant(),fixture.instance().id()).orElseThrow();
        registry.replace(current.successor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),InstanceStatus.COMPLETED,
                current.engineInstanceId(),current.startAttempts(),null,null),current.version(),"test.complete",fixture.owner());
        assertEquals(EffectState.CONFIRMED,resolver.resolve(authority,fixture.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
        assertThrows(RuntimeException.class,()->resolver.resolve(authority,fixture.command().key(),requestId,EffectState.FAILED,"TARGET_REJECTED"));
        assertThrows(RuntimeException.class,()->resolver.resolve(authority,fixture.command().key(),UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED"));
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
        var facts=Transactions.inTransaction(support.db.owner,c->producer.forKey(c,fixture.command().key(),0,128));
        assertEquals("OUTCOME",facts.getLast().source().transition().name());
    }

    @Test void handBuiltAuthorityIsRejectedBeforeAnyDecisionOrRetainedRequest() throws Exception {
        var fixture=unresolved("occur-2");var authority=CallerAuthority.unverified(new io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext(
                fixture.instance().tenant(),fixture.owner(),Set.of("oa.recover")),Clock.systemUTC());
        assertThrows(RuntimeException.class,()->resolver(allowingPep(),new AuditAppendOnce(support.db.owner)).resolve(authority,
                fixture.command().key(),UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED"));
        assertEquals(0,count("select count(*) from effect.manual_decision"));assertEquals(EffectState.INTENDED,ledger.find(fixture.command().key()).orElseThrow().state());
    }

    @Test void tenantMismatchIsRefusedBeforePolicyEvaluation() throws Exception {
        var fixture=unresolved("occur-tenant-mismatch");var calls=new AtomicInteger();var policy=new PolicyRef("test.resolve-effect","1");
        var pep=new Pep(request->{calls.incrementAndGet();return new Decision("test-engine",DecisionOutcome.ALLOW,"allowed",policy,Instant.now());},event->{});
        var now=Instant.now();var token=new VerifiedToken("test-issuer",fixture.owner(),Set.of("test-audience"),Set.of("oa.recover"),
                "another-tenant",now,now.plusSeconds(600),"manual-token-other-tenant",List.of());
        var authority=CallerAuthority.ofUserToken(token,Clock.systemUTC());
        var mismatch=assertThrows(IllegalStateException.class,()->resolver(pep,new AuditAppendOnce(support.db.owner)).resolve(authority,
                fixture.command().key(),UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED"));
        assertEquals("effect has no matching retained verified caller scope",mismatch.getMessage());
        assertEquals(0,calls.get());assertEquals(0,count("select count(*) from effect.manual_decision"));
        assertEquals(0,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        assertEquals(EffectState.INTENDED,ledger.find(fixture.command().key()).orElseThrow().state());
    }

    @Test void boundedManualTransactionsUseReadCommittedIsolation() throws Exception {
        var repeatableRead=withTransactionIsolation(support.db.owner,java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        var isolation=Transactions.inBoundedPostgresTransaction(repeatableRead,c->{
            try(var q=c.createStatement();var r=q.executeQuery("show transaction_isolation")){assertTrue(r.next());return r.getString(1);}
        },2_000,5_000,5_000);
        assertEquals("read committed",isolation);
    }

    @Test void manualResolutionCommitsOutsideAmbientJtaRollback() throws Exception {
        var fixture=unresolved("occur-ambient-rollback");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var requestId=UlidGenerator.shared().next();var resolverConnections=new AtomicInteger();var ledgerConnections=new AtomicInteger();
        var auditConnections=new AtomicInteger();
        var observedLedger=new JdbcEffectLedger(observingSuspendedConnections(ledgerConnections,0),manager,producer);
        var resolver=new ManualEffectResolver(observingSuspendedConnections(resolverConnections,0),manager,allowingPep(),
                new AuditAppendOnce(observingSuspendedConnections(auditConnections,0)),observedLedger);
        manager.begin();var ambient=manager.getTransaction();
        try {
            assertEquals(EffectState.CONFIRMED,resolver.resolve(authority,fixture.command().key(),requestId,
                    EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
            assertSame(ambient,manager.getTransaction());
            assertEquals(2,resolverConnections.get());assertEquals(1,ledgerConnections.get());assertEquals(1,auditConnections.get());
        } finally {
            if(manager.getTransaction()!=null)manager.rollback();
        }
        assertNull(manager.getTransaction());
        assertEquals(EffectState.CONFIRMED,ledger.find(fixture.command().key()).orElseThrow().state());
        assertEquals(1,count("select count(*) from effect.manual_decision"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        var sources=Transactions.inTransaction(support.db.owner,c->producer.forKey(c,fixture.command().key(),0,128));
        assertEquals(AuditSource.Transition.OUTCOME,sources.getLast().source().transition());
    }

    @Test void failedManualRetainRestoresAmbientTransaction() throws Exception {
        var fixture=unresolved("occur-ambient-failure");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var opened=new AtomicInteger();var resolver=new ManualEffectResolver(observingSuspendedConnections(opened,2),manager,allowingPep(),
                new AuditAppendOnce(support.db.owner),ledger);
        manager.begin();var ambient=manager.getTransaction();
        try {
            assertThrows(StoreUnavailableException.class,()->resolver.resolve(authority,fixture.command().key(),UlidGenerator.shared().next(),
                    EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            assertEquals(2,opened.get());assertSame(ambient,manager.getTransaction());
        } finally {
            if(manager.getTransaction()!=null)manager.rollback();
        }
        assertNull(manager.getTransaction());assertEquals(0,count("select count(*) from effect.manual_decision"));
        assertEquals(EffectState.INTENDED,ledger.find(fixture.command().key()).orElseThrow().state());
    }

    @Test void failedManualAuditRestoresAmbientTransactionAndLeavesEffectUnchanged() throws Exception {
        var fixture=unresolved("occur-ambient-audit-failure");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var opened=new AtomicInteger();var resolver=resolver(allowingPep(),new AuditAppendOnce(observingSuspendedConnections(opened,1)));
        manager.begin();var ambient=manager.getTransaction();
        try {
            assertThrows(io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditStoreException.class,()->resolver.resolve(authority,fixture.command().key(),UlidGenerator.shared().next(),
                    EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            assertEquals(1,opened.get());assertSame(ambient,manager.getTransaction());
        } finally {
            if(manager.getTransaction()!=null)manager.rollback();
        }
        assertNull(manager.getTransaction());assertEquals(1,count("select count(*) from effect.manual_decision"));
        assertEquals(0,count("select count(*) from effect.manual_decision_applied"));
        assertEquals(0,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        assertEquals(EffectState.INTENDED,ledger.find(fixture.command().key()).orElseThrow().state());
    }

    @Test void uncertainAuditCommitRetriesSameRequestOutsideAmbientTransaction() throws Exception {
        var fixture=unresolved("occur-ambient-audit-lost-reply");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var requestId=UlidGenerator.shared().next();var opened=new AtomicInteger();
        var uncertain=resolver(allowingPep(),new AuditAppendOnce(lostAuditReplyAndUnavailableReadback(opened)));
        var retryConnections=new AtomicInteger();var retry=resolver(allowingPep(),new AuditAppendOnce(observingSuspendedConnections(retryConnections,0)));
        manager.begin();var ambient=manager.getTransaction();
        try {
            assertThrows(io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditCommitUncertainException.class,
                    ()->uncertain.resolve(authority,fixture.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            assertSame(ambient,manager.getTransaction());assertEquals(2,opened.get());
            assertEquals(1,count("select count(*) from effect.manual_decision"));
            assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
            assertEquals(0,count("select count(*) from effect.manual_decision_applied"));
            assertEquals(EffectState.INTENDED,ledger.find(fixture.command().key()).orElseThrow().state());
            assertEquals(EffectState.CONFIRMED,retry.resolve(authority,fixture.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
            assertSame(ambient,manager.getTransaction());assertEquals(1,retryConnections.get());
        } finally {
            if(manager.getTransaction()!=null)manager.rollback();
        }
        assertNull(manager.getTransaction());assertEquals(1,count("select count(*) from effect.manual_decision"));
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
        assertEquals(EffectState.CONFIRMED,ledger.find(fixture.command().key()).orElseThrow().state());
    }

    @Test void managedPoolEnlistsCallerButManualResolutionSurvivesCallerRollback() throws Exception {
        var fixture=unresolved("occur-managed-audit");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        try(var c=support.db.owner.getConnection();var statement=c.createStatement()) {
            statement.execute("create table public.manual_caller_probe (id integer primary key)");
        }
        var config=new io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier();
        var pool=config.connectionPoolConfiguration();
        pool.maxSize(3).acquisitionTimeout(Duration.ofSeconds(5))
                .transactionIntegration(new io.agroal.narayana.NarayanaTransactionIntegration(manager,transactionRegistry));
        pool.connectionFactoryConfiguration().connectionProviderClass(org.postgresql.Driver.class)
                .jdbcUrl(support.db.owner.getURL()).principal(new io.agroal.api.security.NamePrincipal(support.db.owner.getUser()))
                .credential(new io.agroal.api.security.SimplePassword(support.db.owner.getPassword()));
        try(var managed=io.agroal.api.AgroalDataSource.from(config)) {
            var managedLedger=new JdbcEffectLedger(managed,manager,producer);
            var resolver=new ManualEffectResolver(managed,manager,allowingPep(),new AuditAppendOnce(managed),managedLedger);
            manager.begin();var ambient=manager.getTransaction();
            try {
                try(var connection=managed.getConnection();var statement=connection.createStatement()) {
                    assertEquals(1,statement.executeUpdate("insert into public.manual_caller_probe(id) values (1)"));
                }
                assertEquals(EffectState.CONFIRMED,resolver.resolve(authority,fixture.command().key(),UlidGenerator.shared().next(),
                        EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
                assertSame(ambient,manager.getTransaction());
            } finally {if(manager.getTransaction()!=null)manager.rollback();}
            assertNull(manager.getTransaction());
        }
        assertEquals(0,count("select count(*) from public.manual_caller_probe"));
        assertEquals(1,count("select count(*) from effect.manual_decision"));
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        assertEquals(1,count("select count(*) from audit.source_binding"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
        assertEquals(EffectState.CONFIRMED,ledger.find(fixture.command().key()).orElseThrow().state());
    }

    @Test void manualApplyRequiresAuditReadAccessWithoutAuditWriteAccess() throws Exception {
        var fixture=unresolved("occur-audit-read-grant");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var role="manual_probe_"+UUID.randomUUID().toString().replace("-","");var requestId=UlidGenerator.shared().next();
        try(var c=support.db.owner.getConnection();var statement=c.createStatement()) {
            statement.execute("create role "+role+" nologin noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
        }
        try {
            try(var c=support.db.owner.getConnection();var statement=c.createStatement()) {
                statement.execute("grant usage on schema effect, task, audit to "+role);
                // Broad effect grants are fixture setup, not a proposed production role profile.
                statement.execute("grant all on all tables in schema effect to "+role);
                statement.execute("grant all on all sequences in schema effect to "+role);
                statement.execute("grant select, update on task.instance to "+role);
            }
            var roleData=asRole(role);var roleLedger=new JdbcEffectLedger(roleData,manager,producer);
            var resolver=new ManualEffectResolver(roleData,manager,allowingPep(),new AuditAppendOnce(support.db.owner),roleLedger);
            var denied=assertThrows(StoreUnavailableException.class,()->resolver.resolve(authority,fixture.command().key(),requestId,
                    EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            assertEquals("42501",assertInstanceOf(SQLException.class,denied.getCause()).getSQLState());
            assertEquals(1,count("select count(*) from effect.manual_decision"));
            assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
            assertEquals(0,count("select count(*) from effect.manual_decision_applied"));
            assertEquals(EffectState.INTENDED,ledger.find(fixture.command().key()).orElseThrow().state());
            try(var c=support.db.owner.getConnection();var statement=c.createStatement()) {
                statement.execute("grant select on audit.source_binding, audit.event to "+role);
            }
            try(var c=roleData.getConnection();var statement=c.createStatement();var r=statement.executeQuery(
                    "select has_table_privilege(current_user,'audit.event','INSERT'),has_table_privilege(current_user,'audit.source_binding','INSERT')")) {
                assertTrue(r.next());assertFalse(r.getBoolean(1));assertFalse(r.getBoolean(2));
            }
            assertEquals(EffectState.CONFIRMED,resolver.resolve(authority,fixture.command().key(),requestId,
                    EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
            assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
            assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        } finally {
            try(var c=support.db.owner.getConnection();var statement=c.createStatement()) {
                statement.execute("drop owned by "+role);statement.execute("drop role "+role);
            }
        }
    }

    @Test void requestRetainedBeforePolicyRefusalCanBeSupersededByANewRequestId() throws Exception {
        var f=unresolved("occur-policy-change");var authority=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));var n=new AtomicInteger();
        var deniedRequest=UlidGenerator.shared().next();
        var policy=new PolicyRef("test.resolve-effect","1");var denyOnce=new Pep(request->{
            int call=n.incrementAndGet();return new Decision("test-engine",call==2?DecisionOutcome.DENY:DecisionOutcome.ALLOW,
                    call==2?"policy changed":"allowed",policy,Instant.now());},event->{});
        assertThrows(io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied.class,
                ()->resolver(denyOnce,new AuditAppendOnce(support.db.owner)).resolve(authority,f.command().key(),deniedRequest,EffectState.CONFIRMED,"TARGET_CONFIRMED"));
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());assertEquals(0,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        long decisionId=Transactions.inTransaction(support.db.owner,c->{try(var q=c.prepareStatement("select decision_id from effect.manual_decision where request_id=?")) {
            q.setString(1,deniedRequest.value());try(var r=q.executeQuery()){assertTrue(r.next());return r.getLong(1);}
        }});
        var bypass=assertThrows(io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException.class,
                ()->Transactions.inTransaction(support.db.owner,c->{
                    try(var q=c.prepareStatement("update effect.ledger set state='CONFIRMED',detail='manual resolution',recorded_at=clock_timestamp() where thread_name=? and node_id=? and idempotency_key=?")) {
                        q.setString(1,f.command().key().threadId().value());q.setString(2,f.command().key().nodeId());q.setString(3,f.command().key().idempotencyKey());assertEquals(1,q.executeUpdate());
                    }
                    long eventId;try(var q=c.prepareStatement("insert into effect.event(thread_name,node_id,idempotency_key,from_state,to_state,reason) values(?,? ,?,'INTENDED','CONFIRMED','effect.outcome') returning id")) {
                        q.setString(1,f.command().key().threadId().value());q.setString(2,f.command().key().nodeId());q.setString(3,f.command().key().idempotencyKey());try(var r=q.executeQuery()){assertTrue(r.next());eventId=r.getLong(1);}
                    }
                    try(var q=c.prepareStatement("insert into effect.manual_decision_applied(decision_id,thread_name,node_id,idempotency_key,effect_event_id,resulting_state) values(?,?,?,?,?,'CONFIRMED')")) {
                        q.setLong(1,decisionId);q.setString(2,f.command().key().threadId().value());q.setString(3,f.command().key().nodeId());q.setString(4,f.command().key().idempotencyKey());q.setLong(5,eventId);q.executeUpdate();
                    }
                    return null;
                }));
        assertEquals("23514",assertInstanceOf(SQLException.class,bypass.getCause()).getSQLState());
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());
        var resolved=resolver(allowingPep(),new AuditAppendOnce(support.db.owner)).resolve(authority,f.command().key(),UlidGenerator.shared().next(),EffectState.CONFIRMED,"TARGET_CONFIRMED");
        assertEquals(EffectState.CONFIRMED,resolved.state());assertEquals(2,count("select count(*) from effect.manual_decision"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
    }

    @Test void concurrentSameRequestUsesOneDecisionOneAuditEventAndOneOutcome() throws Exception {
        var f=unresolved("occur-concurrent");var authority=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var policy=new PolicyRef("test.resolve-effect","1");var pep=new Pep(request->
            new Decision("test-engine",DecisionOutcome.ALLOW,"allowed",policy,Instant.now()),event->{},Duration.ofSeconds(10),Clock.systemUTC());
        var resolver=resolver(pep,new AuditAppendOnce(support.db.owner));var requestId=UlidGenerator.shared().next();var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->resolver.resolve(authority,f.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            var b=pool.submit(()->resolver.resolve(authority,f.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            assertEquals(EffectState.CONFIRMED,a.get(20,TimeUnit.SECONDS).state());assertEquals(EffectState.CONFIRMED,b.get(20,TimeUnit.SECONDS).state());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,count("select count(*) from effect.manual_decision"));assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
    }

    @Test void auditAppendFailureLeavesEffectUnchangedAndAnotherRequestCanRecover() throws Exception {
        var f=unresolved("occur-audit-failure");var authority=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));
        DataSource unavailable=(DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},
                (proxy,method,args)->{if(method.getName().equals("getConnection"))throw new SQLException("audit store unavailable");throw new UnsupportedOperationException(method.getName());});
        assertThrows(RuntimeException.class,()->resolver(allowingPep(),new AuditAppendOnce(unavailable)).resolve(authority,f.command().key(),
                UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED"));
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());assertEquals(0,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        assertEquals(EffectState.FAILED,resolver(allowingPep(),new AuditAppendOnce(support.db.owner)).resolve(authority,f.command().key(),
                UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED").state());
        assertEquals(2,count("select count(*) from effect.manual_decision"));assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
    }

    @Test void callerExpiryDuringPolicyRecheckAfterAuditPreventsMutation() throws Exception {
        var f=unresolved("occur-expiry");var clock=new AdvancingClock(Instant.now());var expires=clock.instant().plusSeconds(600);var authority=authority(f,clock,expires);
        var decisions=new AtomicInteger();var policy=new PolicyRef("test.resolve-effect","1");var pep=new Pep(request->{
            if(decisions.incrementAndGet()==4)clock.set(expires);
            return new Decision("test-engine",DecisionOutcome.ALLOW,"allowed",policy,Instant.now());},event->{});
        assertThrows(io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.class,
                ()->resolver(pep,new AuditAppendOnce(support.db.owner)).resolve(authority,f.command().key(),UlidGenerator.shared().next(),EffectState.CONFIRMED,"TARGET_CONFIRMED"));
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        var refreshed=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));
        assertEquals(EffectState.FAILED,resolver(allowingPep(),new AuditAppendOnce(support.db.owner)).resolve(refreshed,f.command().key(),
                UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED").state());
        assertEquals(2,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested'"));
        assertEquals(0,count("select count(*) from audit.event where event_type='effect.manual-resolution'"));
    }

    @Test void slowPolicyRecheckIsBoundedAndReleasesApplyLocks() throws Exception {
        var f=unresolved("occur-slow-policy");var authority=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var policy=new PolicyRef("test.resolve-effect","1");
        var pep=new Pep(request->{
            if(calls.incrementAndGet()==4){entered.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
            return new Decision("test-engine",DecisionOutcome.ALLOW,"allowed",policy,Instant.now());
        },event->{},Duration.ofSeconds(5),Clock.systemUTC());
        try {
            assertThrows(IllegalStateException.class,()->resolver(pep,new AuditAppendOnce(support.db.owner)).resolve(authority,
                    f.command().key(),UlidGenerator.shared().next(),EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());
        } finally {release.countDown();}
        try(var c=support.db.owner.getConnection()) {
            c.setAutoCommit(false);
            try(var q=c.prepareStatement("select id from task.instance where id=? for update nowait")) {
                q.setString(1,f.instance().id().value());try(var r=q.executeQuery()){assertTrue(r.next());}
            }
            c.commit();
        }
        var registry=new JdbcInstanceStore(support.db.owner);var current=registry.find(f.instance().tenant(),f.instance().id()).orElseThrow();
        registry.replace(current.successor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),InstanceStatus.COMPLETED,
                current.engineInstanceId(),current.startAttempts(),null,null),current.version(),"test.complete",f.owner());
    }

    @Test void retainRowLockWaitStopsAtPostgresLockTimeout() throws Exception {
        var f=unresolved("occur-lock-timeout");var authority=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var blocker=support.db.owner.getConnection();blocker.setAutoCommit(false);
        try(var q=blocker.prepareStatement("select id from task.instance where id=? for update")) {
            q.setString(1,f.instance().id().value());try(var r=q.executeQuery()){assertTrue(r.next());}
        }
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->resolver(allowingPep(),new AuditAppendOnce(support.db.owner)).resolve(authority,
                    f.command().key(),UlidGenerator.shared().next(),EffectState.CONFIRMED,"TARGET_CONFIRMED"));
            var failure=assertThrows(ExecutionException.class,()->pending.get(4,TimeUnit.SECONDS));
            var unavailable=assertInstanceOf(io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException.class,failure.getCause());
            assertEquals("55P03",assertInstanceOf(SQLException.class,unavailable.getCause()).getSQLState());
        } finally {blocker.rollback();blocker.close();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());
        assertEquals(0,count("select count(*) from effect.manual_decision"));
    }

    private static final class AdvancingClock extends Clock {
        private volatile Instant value;AdvancingClock(Instant value){this.value=value;}void set(Instant value){this.value=value;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return value;}
    }
}
