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
import java.util.*;
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

    @Test void centralManualDecisionPrecedesAtomicOutcomeAndRetryReusesTheSameEvent() throws Exception {
        var owner=new Subject(SubjectKind.USER,"manual-owner");
        var registry=new JdbcInstanceStore(support.db.owner);
        var request=new StartRequest("tenant-manual",owner,new DefinitionRef("agentic/manual-fixture","1"),Map.of(),"manual-"+UUID.randomUUID());
        var startedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var instance=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,startedAt),"test.start").record();
        registry.replace(instance.successor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),InstanceStatus.RUNNING,"engine-"+instance.id(),1,null,null),instance.version(),"test.running",owner);
        var scope=new EffectScope(instance.tenant(),instance.id(),owner);var command=scope.command("charge","occur-1","approved-fixture","{}");
        ledger.prepareScoped(scope,command,Duration.ZERO,Instant.now());ledger.markAttempted(command.key());
        ledger.requireManualResolution(command.key(),"effect.outcome.unknown");
        var now=Instant.now();var token=new VerifiedToken("test-issuer",owner,Set.of("test-audience"),Set.of("oa.recover"),instance.tenant(),now,now.plusSeconds(600),"manual-token",List.of());
        var authority=CallerAuthority.ofUserToken(token,Clock.systemUTC());var policy=new PolicyRef("test.resolve-effect","1");
        var pep=new Pep(requested->new Decision("test-engine",DecisionOutcome.ALLOW,"allowed",policy,Instant.now()),event->{});
        var resolver=new ManualEffectResolver(support.db.owner,manager,pep,new AuditAppendOnce(support.db.owner),ledger);
        var requestId=UlidGenerator.shared().next();
        var resolved=resolver.resolve(authority,command.key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED");
        assertEquals(EffectState.CONFIRMED,resolved.state());
        assertEquals(1,ResourceExecutionMigrationTest.count(support.db.owner,"select count(*) from audit.event where event_type='effect.manual-resolution'"));
        assertEquals(1,ResourceExecutionMigrationTest.count(support.db.owner,"select count(*) from effect.manual_decision_applied"));
        assertEquals(EffectState.CONFIRMED,resolver.resolve(authority,command.key(),requestId,EffectState.CONFIRMED,"TARGET_CONFIRMED").state());
        assertEquals(1,ResourceExecutionMigrationTest.count(support.db.owner,"select count(*) from audit.event where event_type='effect.manual-resolution'"));
        assertEquals(1,ResourceExecutionMigrationTest.count(support.db.owner,"select count(*) from effect.manual_decision_applied"));
        var facts=Transactions.inTransaction(support.db.owner,c->producer.forKey(c,command.key(),0,128));
        assertEquals("OUTCOME",facts.getLast().source().transition().name());
    }

    @Test void handBuiltAuthorityIsRejectedBeforeAnyDecisionOrRetainedRequest() throws Exception {
        var owner=new Subject(SubjectKind.USER,"manual-owner");
        var registry=new JdbcInstanceStore(support.db.owner);var request=new StartRequest("tenant-manual",owner,new DefinitionRef("agentic/manual-fixture","1"),Map.of(),"manual-"+UUID.randomUUID());
        var startedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var instance=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,startedAt),"test.start").record();
        registry.replace(instance.successor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),InstanceStatus.RUNNING,"engine-"+instance.id(),1,null,null),instance.version(),"test.running",owner);
        var scope=new EffectScope(instance.tenant(),instance.id(),owner);var command=scope.command("charge","occur-2","approved-fixture","{}");
        ledger.prepareScoped(scope,command,Duration.ZERO,Instant.now());ledger.markAttempted(command.key());ledger.requireManualResolution(command.key(),"effect.outcome.unknown");
        var authority=CallerAuthority.unverified(new io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext(instance.tenant(),owner,Set.of("oa.recover")),Clock.systemUTC());
        var pep=new Pep(requested->new Decision("test",DecisionOutcome.ALLOW,"allowed",new PolicyRef("test","1"),Instant.now()),event->{});
        var resolver=new ManualEffectResolver(support.db.owner,manager,pep,new AuditAppendOnce(support.db.owner),ledger);
        assertThrows(RuntimeException.class,()->resolver.resolve(authority,command.key(),UlidGenerator.shared().next(),EffectState.FAILED,"TARGET_REJECTED"));
        assertEquals(0,ResourceExecutionMigrationTest.count(support.db.owner,"select count(*) from effect.manual_decision"));
        assertEquals(EffectState.INTENDED,ledger.find(command.key()).orElseThrow().state());
    }
}
