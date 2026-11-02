package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Actual identity dispatcher with fixture grants/exchange and a local HTTP server; no production composition. */
class GovernedHttpCredentialsTest {
    @Test void liveDispatchKeepsTheExactTokenInScopeAndRefusesRevocation() throws Exception {
        var user=new Subject(SubjectKind.USER,"alice");var agent=new Subject(SubjectKind.AGENT,"worker");
        var grantId=UlidGenerator.shared().next();var instanceId=UlidGenerator.shared().next();var now=Instant.now();
        var authority=new InstanceAuthority(instanceId,"tenant",user,grantId,null,InstanceAuthority.State.AUTHORIZED,null,now,now);
        var grant=new DelegationGrant(grantId,"tenant",user,agent,Set.of("orders:write"),null,List.of("worker"),
                DelegationGrant.Status.ACTIVE,now,user,now.plusSeconds(600),null,null,1);
        var revoked=new AtomicBoolean();var revokeDuringExchange=new AtomicBoolean();var exchanges=new AtomicInteger();
        var registry=new GovernedAsyncDispatch.Registry() {
            public Optional<InstanceAuthority> find(String id){return "engine-1".equals(id)?Optional.of(authority):Optional.empty();}
            public ResumeGate.Decision recheck(Ulid id){return revoked.get()?new ResumeGate.Decision(ResumeGate.Outcome.REAUTHORIZATION_REQUIRED,"revoked"):ResumeGate.Decision.proceed();}
            public void requireReauthorization(InstanceAuthority a,String reason){}
            public Optional<DelegationGrant> grant(Ulid id){return grantId.equals(id)?Optional.of(grant):Optional.empty();}
        };
        var credentials=new GovernedAsyncDispatch.Credentials() {
            public Optional<Credential> subject(InstanceAuthority a){return Optional.of(Credential.of("subject"));}
            public Credential actor(){return Credential.of("actor");}
        };
        var dispatch=new GovernedAsyncDispatch(registry,credentials,request->{
            exchanges.incrementAndGet();if(revokeDuringExchange.get())revoked.set(true);
            return new IssuedToken(Credential.of("issued"),request.audience(),request.scopes(),Instant.now().plusSeconds(90),
                    "issuer","jti",List.of("worker"),grantId);
        },Clock.systemUTC());
        var calls=new AtomicInteger();var revokeOnSend=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/orders",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            if(revokeOnSend.get())revoked.set(true);exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/orders");
            var expected=new PinnedHttpEffectTarget.CredentialExpectation("issuer",grantId,Set.of("orders:write"));
            var provider=new GovernedHttpCredentials(dispatch,"engine-1",Set.of("orders:write"));
            var target=new PinnedHttpEffectTarget("tool:orders",endpoint,"orders","corr","{}",Duration.ofSeconds(3),
                    Set.of(200),expected,provider,token->{
                        assertEquals(authority,AuthorityScope.current().orElseThrow());
                        assertEquals(grantId,token.grantId());dispatch.requireCurrentAuthority("engine-1");
                    },true);
            assertEquals(EffectTarget.Outcome.CONFIRMED,target.execute("key-1").outcome());
            assertTrue(AuthorityScope.current().isEmpty());assertEquals(1,calls.get());assertEquals(1,exchanges.get());
            revokeDuringExchange.set(true);
            assertThrows(AuthorityLostException.class,()->target.execute("key-2"));
            assertEquals(1,calls.get(),"revocation during exchange must precede the target call");
            revoked.set(false);revokeDuringExchange.set(false);revokeOnSend.set(true);
            assertThrows(AuthorityLostException.class,()->target.execute("key-3"));
            assertEquals(2,calls.get(),"a completed external call is not undone by later revocation");
            assertTrue(AuthorityScope.current().isEmpty());
        } finally {server.stop(0);}
    }

    @Test void escapedOrRepeatedCredentialCallbacksCannotSendAnotherRequest() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/orders",e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,-1);e.close();});server.start();
        try {
            var grant=UlidGenerator.shared().next();var token=new IssuedToken(Credential.of("issued"),"orders",Set.of("orders:write"),
                    Instant.now().plusSeconds(60),"issuer","jti",List.of("worker"),grant);
            var expected=new PinnedHttpEffectTarget.CredentialExpectation("issuer",grant,Set.of("orders:write"));
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/orders");
            var escaped=new AtomicReference<PinnedHttpEffectTarget.TokenAction>();
            var noCallback=new PinnedHttpEffectTarget("tool:orders",endpoint,"orders","corr","{}",Duration.ofSeconds(3),
                    Set.of(200),expected,(a,action)->{escaped.set(action);return new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,null);},t->{},true);
            assertThrows(IllegalStateException.class,()->noCallback.execute("key-1"));
            assertThrows(IllegalStateException.class,()->escaped.get().run(token));assertEquals(0,calls.get());
            var repeated=new PinnedHttpEffectTarget("tool:orders",endpoint,"orders","corr","{}",Duration.ofSeconds(3),
                    Set.of(200),expected,(a,action)->{var first=action.run(token);assertThrows(IllegalStateException.class,()->action.run(token));return first;},t->{},true);
            assertEquals(EffectTarget.Outcome.CONFIRMED,repeated.execute("key-2").outcome());assertEquals(1,calls.get());
        } finally {server.stop(0);}
    }
}
