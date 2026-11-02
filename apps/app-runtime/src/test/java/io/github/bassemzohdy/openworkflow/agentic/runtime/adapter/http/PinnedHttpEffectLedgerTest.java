package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthorities;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthorityStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GovernedAsyncDispatch;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import java.time.Instant;
import java.util.List;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.DurableEffects;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.*;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Local test transport and real ledger only; not production authorization, provider or OpenAPI integration. */
@QuarkusTest
class PinnedHttpEffectLedgerTest {
    @Inject DurableEffects effects;
    @Inject JdbcEffectLedger ledger;
    @Inject JdbcInstanceStore instances;
    @Inject InstanceAuthorities authorities;
    @Inject DelegationGrants grants;
    @Inject InstanceAuthorityStore authorityStore;

    @Test void rebindDuringExchangeDoesNotRunOldGrantWork() {
        rebindDuringDispatch(true);
    }

    @Test void rebindDuringWorkDoesNotReleaseOldGrantResult() {
        rebindDuringDispatch(false);
    }

    private void rebindDuringDispatch(boolean duringExchange) {
        String tenant="http-rebind-"+UUID.randomUUID();
        var user=new Subject(SubjectKind.USER,"alice");var agent=new Subject(SubjectKind.AGENT,"worker");
        var oldGrant=grants.grant(tenant,user,user,agent,Set.of("orders:write"),Instant.now().plusSeconds(600),null);
        var newGrant=grants.grant(tenant,user,user,agent,Set.of("orders:write"),Instant.now().plusSeconds(600),null);
        var request=new StartRequest(tenant,user,new DefinitionRef("demo/start","1"),Map.of(),"start-key");
        var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var candidate=InstanceRecord.starting(UlidGenerator.shared().next(),request,now);
        var row=instances.insertIfAbsent(candidate,"test.insert",authorities.bindAtStart(tenant,user,oldGrant.id(),"corr-test")).record();
        String engineId="http-rebind-"+UUID.randomUUID();
        instances.replace(row.successor(now,InstanceStatus.STARTING,engineId,0,null,null),row.version(),"test.prepared",user);
        Runnable rebind=()->{
            assertTrue(authorityStore.markReauthorizationRequired(row.id(),"test.rebind",Instant.now()));
            assertTrue(authorityStore.rebind(row.id(),oldGrant.id(),newGrant.id(),Instant.now()));
        };
        var creds=new GovernedAsyncDispatch.Credentials() {
            public Optional<Credential> subject(InstanceAuthority a){return Optional.of(Credential.of("subject"));}
            public Credential actor(){return Credential.of("actor");}
        };
        var dispatch=new GovernedAsyncDispatch(GovernedAsyncDispatch.over(authorities,grants),creds,exchange->{
            if(duringExchange)rebind.run();
            return new IssuedToken(Credential.of("issued"),exchange.audience(),exchange.scopes(),Instant.now().plusSeconds(60),
                    "issuer","jti",List.of("worker"),oldGrant.id());
        },Clock.systemUTC());
        var ran=new AtomicInteger();
        assertThrows(AuthorityLostException.class,()->dispatch.run(engineId,"orders",Set.of("orders:write"),token->{
            ran.incrementAndGet();if(!duringExchange)rebind.run();return "output";
        }));
        assertEquals(duringExchange?0:1,ran.get());
        assertEquals(newGrant.id(),authorities.find(row.id()).orElseThrow().grantId());
    }

    @Test void revocationDuringCallRetainsConfirmedFactWithoutReleasingIt() throws Exception {
        revokeDuringCall(200, EffectState.CONFIRMED);
    }

    @Test void revocationDuringUnconfirmedCallWithholdsAndQueuesTheUnknown() throws Exception {
        revokeDuringCall(202, EffectState.INTENDED);
    }

    @Test void revocationAfterDocumentedRejectionRetainsFailureWithoutReleasingIt() throws Exception {
        revokeDuringCall(422, EffectState.FAILED);
    }

    private void revokeDuringCall(int status, EffectState expectedState) throws Exception {
        String tenant="http-"+UUID.randomUUID();var user=new Subject(SubjectKind.USER,"alice");
        var agent=new Subject(SubjectKind.AGENT,"worker");
        var grant=grants.grant(tenant,user,user,agent,Set.of("orders:write"),Instant.now().plusSeconds(600),null);
        var request=new StartRequest(tenant,user,new DefinitionRef("demo/start","1"),Map.of(),"start-key");
        var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var candidate=InstanceRecord.starting(UlidGenerator.shared().next(),request,now);
        var row=instances.insertIfAbsent(candidate,"test.insert",authorities.bindAtStart(tenant,user,grant.id(),"corr-test")).record();
        String engineId="http-"+UUID.randomUUID();
        instances.replace(row.successor(now,InstanceStatus.STARTING,engineId,0,null,null),row.version(),"test.prepared",user);
        var creds=new GovernedAsyncDispatch.Credentials() {
            public Optional<Credential> subject(InstanceAuthority a){return Optional.of(Credential.of("subject"));}
            public Credential actor(){return Credential.of("actor");}
        };
        var dispatch=new GovernedAsyncDispatch(GovernedAsyncDispatch.over(authorities,grants),creds,exchange->
                new IssuedToken(Credential.of("issued"),exchange.audience(),exchange.scopes(),Instant.now().plusSeconds(60),
                        "issuer","jti",List.of("worker"),grant.id()),Clock.systemUTC());
        var failure=new AtomicReference<Throwable>();var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/orders",exchange->{
            try {calls.incrementAndGet();exchange.getRequestBody().readAllBytes();grants.revoke(grant.id(),user,"withdrawn during call",null);
                exchange.sendResponseHeaders(status,-1);
            } catch(Throwable e) {failure.set(e);try{exchange.sendResponseHeaders(500,-1);}catch(java.io.IOException ignored){}}
            finally {exchange.close();}
        });server.start();
        try {
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/orders");
            var expected=new PinnedHttpEffectTarget.CredentialExpectation("issuer",grant.id(),Set.of("orders:write"));
            var target=new PinnedHttpEffectTarget("tool:orders",endpoint,"orders","correlation","{}",Duration.ofSeconds(5),
                    Set.of(200),Set.of(422),expected,new GovernedHttpCredentials(dispatch,engineId,Set.of("orders:write")),
                    issued->dispatch.requireCurrentAuthority(engineId),true,false);
            var thread=ThreadId.derive("http-revocation-test","alice",UUID.randomUUID().toString());
            var command=EffectCommand.of(thread,"send","occurrence-1","tool:orders","{}");
            assertThrows(AuthorityLostException.class,()->effects.execute(command,target));
            assertEquals(expectedState,ledger.find(command.key()).orElseThrow().state());
            assertNull(failure.get());assertEquals(1,calls.get());
            assertEquals(expectedState == EffectState.INTENDED ? 1 : 0,ledger.manualQueue(thread,10).size());
            assertEquals(InstanceAuthority.State.REAUTHORIZATION_REQUIRED,authorities.find(row.id()).orElseThrow().state());
        } finally {server.stop(0);}
    }

    @Test void lostAcknowledgmentStaysUnresolvedWithoutSendingASecondRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        var entered=new CountDownLatch(1);var calls=new AtomicInteger();
        server.createContext("/orders",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();entered.countDown();
            try {Thread.sleep(1200);exchange.sendResponseHeaders(200,-1);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}catch(java.io.IOException ignored){} finally {exchange.close();}
        });server.start();
        try {
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/orders");
            var grant=UlidGenerator.shared().next();
            var expected=new PinnedHttpEffectTarget.CredentialExpectation("issuer",grant,Set.of("orders:write"));
            var target=new PinnedHttpEffectTarget("tool:orders",endpoint,"orders","correlation","{\"amount\":10}",
                    Duration.ofMillis(500),Set.of(200),expected,(audience,action)->action.run(new IssuedToken(Credential.of("token"),audience,
                    Set.of("orders:write"),Instant.now().plusSeconds(60),"issuer","jti",List.of("actor"),grant)),issued->{},true);
            var thread=ThreadId.derive("http-effect-test","alice",UUID.randomUUID().toString());
            var command=EffectCommand.of(thread,"send","occurrence-1","tool:orders","{\"amount\":10}");
            assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
            assertEquals(1,calls.get(),"a zero replay window must not send the unresolved effect again");
            assertEquals(1,ledger.manualQueue(thread,10).size());
        } finally {server.stop(0);executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);}
    }

    @Test void authoritativeLookupRecoversLostAcknowledgmentWithoutResending() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var posts=new AtomicInteger();var lookups=new AtomicInteger();var key=new AtomicReference<String>();
        server.createContext("/orders",exchange->{try {posts.incrementAndGet();key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(202,-1);} finally {exchange.close();}});
        server.createContext("/lookup",exchange->{try {int attempt=lookups.incrementAndGet();
            boolean same="GET".equals(exchange.getRequestMethod()) && key.get().equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.sendResponseHeaders(same && attempt>20 ? 200 : 404,-1);} finally {exchange.close();}});
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();var grant=UlidGenerator.shared().next();
            var expected=new PinnedHttpEffectTarget.CredentialExpectation("issuer",grant,Set.of("orders:write"));
            var lookup=new PinnedHttpEffectTarget.Lookup(URI.create(base+"/lookup"),Set.of(200));
            var target=new PinnedHttpEffectTarget("tool:orders",URI.create(base+"/orders"),"orders","correlation","{}",
                    Duration.ofSeconds(5),Set.of(200),Set.of(),expected,(audience,action)->action.run(new IssuedToken(
                    Credential.of("token"),audience,Set.of("orders:write"),Instant.now().plusSeconds(60),"issuer","jti",List.of("actor"),grant)),
                    issued->{},true,false,lookup);
            var thread=ThreadId.derive("http-lookup-test","alice",UUID.randomUUID().toString());
            var command=EffectCommand.of(thread,"send","occurrence-1","tool:orders","{}");
            assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
            assertEquals(1,ledger.manualQueue(thread,10).size());
            for(int attempt=0;attempt<20;attempt++) assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
            assertEquals(1,posts.get());assertEquals(20,lookups.get());
            assertEquals(EffectState.CONFIRMED,effects.execute(command,target).state());
            assertEquals(EffectState.CONFIRMED,effects.execute(command,target).state());
            assertEquals(1,posts.get());assertEquals(21,lookups.get());assertTrue(ledger.manualQueue(thread,10).isEmpty());
        } finally {server.stop(0);}
    }

    @Test void lookupConfirmationIsRetainedWhenGrantIsRevokedDuringLookup() throws Exception {
        String tenant="http-lookup-"+UUID.randomUUID();var user=new Subject(SubjectKind.USER,"alice");
        var agent=new Subject(SubjectKind.AGENT,"worker");
        var grant=grants.grant(tenant,user,user,agent,Set.of("orders:write"),Instant.now().plusSeconds(600),null);
        var request=new StartRequest(tenant,user,new DefinitionRef("demo/start","1"),Map.of(),"start-key");
        var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var row=instances.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now),"test.insert",
                authorities.bindAtStart(tenant,user,grant.id(),"corr-test")).record();
        String engineId="http-lookup-"+UUID.randomUUID();
        instances.replace(row.successor(now,InstanceStatus.STARTING,engineId,0,null,null),row.version(),"test.prepared",user);
        var creds=new GovernedAsyncDispatch.Credentials() {
            public Optional<Credential> subject(InstanceAuthority a){return Optional.of(Credential.of("subject"));}
            public Credential actor(){return Credential.of("actor");}
        };
        var dispatch=new GovernedAsyncDispatch(GovernedAsyncDispatch.over(authorities,grants),creds,exchange->
                new IssuedToken(Credential.of("issued"),exchange.audience(),exchange.scopes(),Instant.now().plusSeconds(60),
                        "issuer","jti",List.of("worker"),grant.id()),Clock.systemUTC());
        var failure=new AtomicReference<Throwable>();var posts=new AtomicInteger();var lookups=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/orders",exchange->{try{posts.incrementAndGet();exchange.sendResponseHeaders(202,-1);}finally{exchange.close();}});
        server.createContext("/lookup",exchange->{try{lookups.incrementAndGet();grants.revoke(grant.id(),user,"withdrawn during lookup",null);
            exchange.sendResponseHeaders(200,-1);}catch(Throwable e){failure.set(e);try{exchange.sendResponseHeaders(500,-1);}catch(java.io.IOException ignored){}}
            finally{exchange.close();}});server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var target=new PinnedHttpEffectTarget("tool:orders",URI.create(base+"/orders"),"orders","correlation","{}",Duration.ofSeconds(5),
                    Set.of(200),Set.of(),new PinnedHttpEffectTarget.CredentialExpectation("issuer",grant.id(),Set.of("orders:write")),
                    new GovernedHttpCredentials(dispatch,engineId,Set.of("orders:write")),issued->dispatch.requireCurrentAuthority(engineId),
                    true,false,new PinnedHttpEffectTarget.Lookup(URI.create(base+"/lookup"),Set.of(200)));
            var thread=ThreadId.derive("http-lookup-revoked","alice",UUID.randomUUID().toString());
            var command=EffectCommand.of(thread,"send","occurrence-1","tool:orders","{}");
            assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
            assertThrows(AuthorityLostException.class,()->effects.execute(command,target));
            assertEquals(EffectState.CONFIRMED,ledger.find(command.key()).orElseThrow().state());
            assertEquals(1,posts.get());assertEquals(1,lookups.get());assertNull(failure.get());
            assertTrue(ledger.manualQueue(thread,10).isEmpty());
        } finally {server.stop(0);}
    }
}
