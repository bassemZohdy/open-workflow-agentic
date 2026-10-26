package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Real PG/control/identity mechanics. Fixture verifier and identity-only fence are NOT production composition. */
class ResourceRunningControlTest {
    @Test void onlyAFreshGovernedCallbackReceivesControlAndItCannotOutliveItsAttempt() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var checks=new AtomicInteger();
            var fence=CredentialFence.pair(f.binding(),entry->{assertEquals(f.entry(1),entry);checks.incrementAndGet();});
            var attempt=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),fence);
            var result=attempt.executeGoverned(running->{
                running.requireCurrent(f.entry(1));
                assertThrows(RefusedException.class,()->running.requireCurrent(f.entry(2)));
                assertThrows(NotSerializableException.class,()->new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(running));
                assertThrows(JsonProcessingException.class,()->new ObjectMapper().writeValueAsString(running));
                assertThrows(NotSerializableException.class,()->new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(fence));
                assertThrows(JsonProcessingException.class,()->new ObjectMapper().writeValueAsString(fence));
                return running;
            });
            assertEquals(Kind.CALLED,result.kind());assertTrue(checks.get()>0);
            var captured=result.value();assertEquals(f.entry(1),captured.entry());
            assertThrows(RefusedException.class,captured::requireCurrent);captured.close();captured.close();
            assertEquals(0,gate.activeCount());
            var again=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),fence)
                    .executeGoverned(running->{fail("retained data must not mint running control");return running;});
            assertEquals(Kind.RETAINED,again.kind());assertNull(again.value());
        }
    }
    @Test void wrongCredentialAssociationAndWrongCallbackModeRefuseWithoutEntry() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var now=databaseNow(db.owner);
            var other=credential(db,f,now.minusSeconds(1),now.plusSeconds(120));
            assertEquals(other.scope(),f.binding().scope());
            assertThrows(RefusedException.class,()->gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(other,e->{ })));
            assertEquals(0,gate.activeCount());
            try(var plain=gate.open(f.binding(),f.entry(1),UUID.randomUUID(),e->{ })) {
                assertThrows(RefusedException.class,()->plain.executeGoverned(r->{fail("legacy data callback cannot expose control");return null;}));
            }
            try(var governed=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(f.binding(),e->{ }))) {
                assertThrows(RefusedException.class,()->governed.execute(e->{fail("governed path requires private control");return null;}));
            }
            assertTrue(gate.find(f.binding().scope()).isEmpty());
        }
    }
    @Test void aNodeOnAnotherThreadObservesCloseAndCapacityWaitsForItsActualTermination() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            var caller=Executors.newSingleThreadExecutor();var node=Executors.newSingleThreadExecutor();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var captured=new AtomicReference<Running>();
            var parentThread=new AtomicReference<Thread>();
            var attempt=gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(f.binding(),e->{ }));
            try {
                var work=caller.submit(()->attempt.executeGoverned(running->{
                    parentThread.set(Thread.currentThread());captured.set(running);
                    try {
                        var action=node.submit(()->{
                            assertNotEquals(parentThread.get(),Thread.currentThread());running.requireCurrent(f.entry(1));
                            entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));
                            assertThrows(RefusedException.class,running::requireCurrent);return "private-node-value";
                        });
                        return assertDoesNotThrow(()->action.get(10,TimeUnit.SECONDS));
                    } finally {
                        node.shutdown();assertTrue(assertDoesNotThrow(()->node.awaitTermination(10,TimeUnit.SECONDS)));
                    }
                }));
                assertTrue(entered.await(10,TimeUnit.SECONDS));captured.get().close();
                assertThrows(RefusedException.class,captured.get()::requireCurrent);
                assertEquals(1,gate.activeCount());assertFalse(work.isDone());
                assertThrows(RefusedException.class,()->gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(f.binding(),e->{ })));
                release.countDown();var failure=assertThrows(ExecutionException.class,()->work.get(10,TimeUnit.SECONDS));
                assertInstanceOf(RefusedException.class,failure.getCause());assertEquals(0,gate.activeCount());
                assertTrue(node.isTerminated());assertThrows(RefusedException.class,captured.get()::requireCurrent);
            } finally {
                release.countDown();node.shutdownNow();caller.shutdownNow();
                assertTrue(node.awaitTermination(10,TimeUnit.SECONDS));assertTrue(caller.awaitTermination(10,TimeUnit.SECONDS));
            }
        }
    }
    @Test void realOpaquePairingAndCanonicalLookupFeedTheGateWithoutCredentialSwapping() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,2)) {
            db.migrate(null);var f=fixture(db,"a".repeat(64),Pep.AGENT_RESEARCH_RUN);var b=f.binding();
            var now=databaseNow(db.owner);var b2=credential(db,f,now.minusSeconds(1),now.plusSeconds(120));
            Subject expectedWorkload=new Subject(SubjectKind.WORKFLOW,"execution-schema-fixture"); // independent server fixture pin
            Clock clock=Clock.systemUTC();var ids=UlidGenerator.shared();
            var grants=new DelegationGrants(new DelegationGrantStore(db.owner),new IdentityAudit(event->{ },clock,ids),clock,ids,Duration.ofHours(2));
            var issued=new JdbcIssuedTokenBindings(db.owner);var calls=new JdbcResourceCallBindings(db.owner);
            var tokens=new HashMap<String,VerifiedToken>();
            for(var binding:List.of(b,b2))tokens.put("fixture-only-bearer-"+binding.tokenId(),new VerifiedToken(
                    binding.issuer(),binding.scope().owner(),Set.of(binding.scope().audience()),Set.of(AgentResourceAdmission.SCOPE),
                    binding.scope().tenant(),binding.issuedAt(),binding.expiresAt(),binding.tokenId(),List.of(expectedWorkload.id())));
            var guard=new ResourceTokenGuard((bearer,audience)->{
                var token=tokens.get(bearer);if(token==null)throw new TokenRejectedException(TokenRejectedException.Reason.SIGNATURE);return token;
            },b.scope().audience(),new JdbcReplayCache(db.owner),grants,issued,clock,1);
            var admission=new AgentResourceAdmission(guard,calls,issued,b.scope().audience(),clock);
            try {
                var first=admission.bind(admission.admitCall("fixture-only-bearer-"+b.tokenId()),f.entry(1));
                var second=admission.bind(admission.admitCall("fixture-only-bearer-"+b2.tokenId()),f.entry(1));
                var one=calls.findBinding(first.bindingKey()).orElseThrow();var two=calls.findBinding(second.bindingKey()).orElseThrow();
                assertEquals(b,one);assertEquals(b2,two);assertNotEquals(first.bindingKey(),second.bindingKey());
                var live=new LiveIdentityCheck(admission);var callbacks=new AtomicInteger();
                var swapped=gate.openGoverned(one,f.entry(1),UUID.randomUUID(),CredentialFence.pair(one,
                        entry->live.requireLive(second,entry,expectedWorkload,one)));
                assertThrows(AgentResourceAdmission.WitnessRefusedException.class,()->swapped.executeGoverned(r->{callbacks.incrementAndGet();return "unsafe";}));
                assertTrue(gate.find(b.scope()).isEmpty());
                var exact=gate.openGoverned(one,f.entry(1),UUID.randomUUID(),CredentialFence.pair(one,
                        entry->live.requireLive(first,entry,expectedWorkload,one)));
                assertEquals(Kind.CALLED,exact.executeGoverned(r->{r.requireCurrent(f.entry(1));callbacks.incrementAndGet();return "fixture";}).kind());
                var refreshed=gate.openGoverned(two,f.entry(1),UUID.randomUUID(),CredentialFence.pair(two,
                        entry->live.requireLive(second,entry,expectedWorkload,two)));
                assertEquals(Kind.RETAINED,refreshed.executeGoverned(r->{callbacks.incrementAndGet();return "unsafe";}).kind());
                assertEquals(1,callbacks.get());assertEquals(one.key(),gate.find(b.scope()).orElseThrow().firstBindingKey());
            } finally {admission.close();}
        }
    }
}
