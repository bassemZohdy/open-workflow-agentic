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

    @Test void centralManualDecisionPrecedesAtomicOutcomeAndRetryReusesTheSameEvent() throws Exception {
        var fixture=unresolved("occur-1");var authority=authority(fixture,Clock.systemUTC(),Instant.now().plusSeconds(600));
        var resolver=resolver(allowingPep(),new AuditAppendOnce(support.db.owner));
        var requestId=UlidGenerator.shared().next();
        var resolved=resolver.resolve(authority,fixture.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED");
        assertEquals(EffectState.CONFIRMED,resolved.state());
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution'"));assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
        assertEquals(EffectState.CONFIRMED,resolver.resolve(authority,fixture.command().key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
        assertThrows(RuntimeException.class,()->resolver.resolve(authority,fixture.command().key(),requestId,EffectState.FAILED,"TARGET_REJECTED"));
        assertThrows(RuntimeException.class,()->resolver.resolve(authority,fixture.command().key(),UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED"));
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution'"));assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
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

    @Test void requestRetainedBeforePolicyRefusalCanBeSupersededByANewRequestId() throws Exception {
        var f=unresolved("occur-policy-change");var authority=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));var n=new AtomicInteger();
        var policy=new PolicyRef("test.resolve-effect","1");var denyOnce=new Pep(request->{
            int call=n.incrementAndGet();return new Decision("test-engine",call==2?DecisionOutcome.DENY:DecisionOutcome.ALLOW,
                    call==2?"policy changed":"allowed",policy,Instant.now());},event->{});
        assertThrows(io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied.class,
                ()->resolver(denyOnce,new AuditAppendOnce(support.db.owner)).resolve(authority,f.command().key(),UlidGenerator.shared().next(),EffectState.CONFIRMED,"TARGET_CONFIRMED"));
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());assertEquals(0,count("select count(*) from audit.event where event_type='effect.manual-resolution'"));
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
        assertEquals(1,count("select count(*) from effect.manual_decision"));assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution'"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied"));
    }

    @Test void auditAppendFailureLeavesEffectUnchangedAndAnotherRequestCanRecover() throws Exception {
        var f=unresolved("occur-audit-failure");var authority=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));
        DataSource unavailable=(DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},
                (proxy,method,args)->{if(method.getName().equals("getConnection"))throw new SQLException("audit store unavailable");throw new UnsupportedOperationException(method.getName());});
        assertThrows(RuntimeException.class,()->resolver(allowingPep(),new AuditAppendOnce(unavailable)).resolve(authority,f.command().key(),
                UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED"));
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());assertEquals(0,count("select count(*) from audit.event where event_type='effect.manual-resolution'"));
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
        assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution'"));
        var refreshed=authority(f,Clock.systemUTC(),Instant.now().plusSeconds(600));
        assertEquals(EffectState.CONFIRMED,resolver(allowingPep(),new AuditAppendOnce(support.db.owner)).resolve(refreshed,f.command().key(),
                UlidGenerator.shared().next(),EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
    }

    private static final class AdvancingClock extends Clock {
        private volatile Instant value;AdvancingClock(Instant value){this.value=value;}void set(Instant value){this.value=value;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return value;}
    }
}
