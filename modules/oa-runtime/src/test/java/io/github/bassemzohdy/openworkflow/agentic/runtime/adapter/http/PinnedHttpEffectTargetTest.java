package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget.Outcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedToken;
import java.time.Instant;
import java.util.List;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PinnedHttpEffectTargetTest {
    private static final Duration TIMEOUT=Duration.ofSeconds(3);
    private static final io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid GRANT=UlidGenerator.shared().next();
    private static final PinnedHttpEffectTarget.CredentialExpectation EXPECTED=
            new PinnedHttpEffectTarget.CredentialExpectation("issuer",GRANT,Set.of("orders:write"));
    private static IssuedToken token(String audience) {
        return new IssuedToken(Credential.of("token.value"),audience,Set.of("orders:write"),Instant.now().plusSeconds(60),
                "issuer","jti",List.of("actor"),GRANT);
    }
    private static PinnedHttpEffectTarget operation(URI uri,Duration timeout) {
        return new PinnedHttpEffectTarget("tool:orders",uri,"audience:orders","trace-1","{\"amount\":10}",
                timeout,Set.of(200),EXPECTED,(audience,action)->{assertEquals("audience:orders",audience);return action.run(token(audience));},issued->{},true);
    }
    private static URI uri(HttpServer server,String path) {return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path);}
    @Test void sendsBoundRequestAndRecognizesOnlyConfiguredTerminalConfirmation() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var seen=new AtomicInteger();var key=new AtomicReference<String>();var auth=new AtomicReference<String>();
        var correlation=new AtomicReference<String>();var body=new AtomicReference<String>();
        server.createContext("/orders",exchange->{
            seen.incrementAndGet();key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200,-1);exchange.close();
        });server.start();
        try {
            var supplied=token("audience:orders");var checked=new AtomicReference<IssuedToken>();
            var target=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1",
                    "{\"amount\":10}",TIMEOUT,Set.of(200),EXPECTED,(a,action)->action.run(supplied),checked::set,true);
            assertEquals(Duration.ZERO,target.replayWindow());assertEquals(Outcome.CONFIRMED,target.execute("stable-key").outcome());
            assertSame(supplied,checked.get(),"the fence receives the exact credential being sent");
            assertEquals(1,seen.get());assertEquals("stable-key",key.get());assertEquals("Bearer token.value",auth.get());
            assertEquals("trace-1",correlation.get());assertEquals("{\"amount\":10}",body.get());
            assertEquals(Outcome.UNKNOWN,target.lookup("stable-key").outcome());
        } finally {server.stop(0);}
    }
    @Test void propagatesOnlyTraceparentOnDispatchAndLookup() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var parents=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var sensitive=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{
            try {
                parents.add(java.util.Objects.toString(exchange.getRequestHeaders().getFirst("traceparent"),""));
                sensitive.add(java.util.Objects.toString(exchange.getRequestHeaders().getFirst("baggage"),"")
                        +java.util.Objects.toString(exchange.getRequestHeaders().getFirst("tracestate"),""));
                exchange.sendResponseHeaders(200,-1);
            } finally {exchange.close();}
        });server.start();
        try {
            var lookup=new PinnedHttpEffectTarget.Lookup(uri(server,"/lookup"),Set.of(200));
            var target=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),Set.of(),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false,lookup);
            var span=io.opentelemetry.api.trace.Span.wrap(io.opentelemetry.api.trace.SpanContext.create(
                    "0123456789abcdef0123456789abcdef","0123456789abcdef",io.opentelemetry.api.trace.TraceFlags.getSampled(),
                    io.opentelemetry.api.trace.TraceState.builder().put("tenant","private-value").build()));
            var baggage=io.opentelemetry.api.baggage.Baggage.builder().put("secret","private-value").build();
            try(var scope=io.opentelemetry.context.Context.current().with(span).with(baggage).makeCurrent()) {
                assertEquals(Outcome.CONFIRMED,target.execute("key").outcome());
                assertEquals(Outcome.CONFIRMED,target.lookup("key").outcome());
            }
            assertEquals(Outcome.CONFIRMED,target.execute("no-trace").outcome());
            assertEquals(java.util.List.of("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                    "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",""),parents);
            assertEquals(java.util.List.of("","",""),sensitive);
        } finally {server.stop(0);}
    }
    @Test void authorityLostDuringRequestPreparationRefusesBeforeNetworkIo() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/orders",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {
            var checks=new AtomicInteger();
            var target=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),EXPECTED,(a,action)->action.run(token(a)),issued->{
                        if(checks.incrementAndGet()==2)throw new io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException(
                                io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.Reason.REVOKED);
                    },true);
            assertThrows(io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.class,()->target.execute("key"));
            assertEquals(2,checks.get());assertEquals(0,calls.get());
        } finally {server.stop(0);}
    }
    @Test void redirectIsNotFollowedAndUnrecognizedResponseIsUnknown() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var redirected=new AtomicInteger();
        server.createContext("/start",exchange->{exchange.getResponseHeaders().add("Location","/other");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/other",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {assertEquals(Outcome.UNKNOWN,operation(uri(server,"/start"),TIMEOUT).execute("key").outcome());assertEquals(0,redirected.get());}
        finally {server.stop(0);}
    }
    @Test void onlyExplicitlyPinnedBusinessRejectionsAreTerminal() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/orders",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(422,-1);exchange.close();});server.start();
        try {
            URI endpoint=uri(server,"/orders");
            assertEquals(Outcome.UNKNOWN,operation(endpoint,TIMEOUT).execute("unconfigured").outcome());
            var configured=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),Set.of(422),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false);
            assertEquals(Outcome.FAILED,configured.execute("configured").outcome());
            assertEquals(2,calls.get());
            assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),Set.of(500),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false));
        } finally {server.stop(0);}
    }
    @Test void scopedLookupUsesSameKeyAndOnlySelectedConfirmationIsFinal() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        var key=new AtomicReference<String>();var auth=new AtomicReference<String>();
        server.createContext("/lookup",exchange->{
            try { assertEquals("GET",exchange.getRequestMethod()); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(calls.incrementAndGet()==1 ? 404 : 200,-1);
            } finally {exchange.close();}
        });server.start();
        try {
            var lookup=new PinnedHttpEffectTarget.Lookup(uri(server,"/lookup"),Set.of(200));
            var target=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),Set.of(),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false,lookup);
            assertEquals(Outcome.UNKNOWN,target.lookup("stable-key").outcome());
            assertEquals(Outcome.CONFIRMED,target.lookup("stable-key").outcome());
            assertEquals("stable-key",key.get());assertEquals("Bearer token.value",auth.get());assertEquals(2,calls.get());
            assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget.Lookup(uri(server,"/lookup?x=1"),Set.of(200)));
            assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget.Lookup(uri(server,"/lookup"),Set.of(404)));
            assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),Set.of(),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false,
                    new PinnedHttpEffectTarget.Lookup(URI.create("http://127.0.0.1:1/lookup"),Set.of(200))));
        } finally {server.stop(0);}
    }
    @Test void oversizedLookupBodyCannotConfirmAnEffect() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/small",exchange->{try {byte[] body="private response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});
        server.createContext("/lookup",exchange->{try {byte[] body=new byte[65_537];exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);}catch(java.io.IOException ignored){}finally{exchange.close();}});server.start();
        try {
            var small=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),Set.of(),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false,
                    new PinnedHttpEffectTarget.Lookup(uri(server,"/small"),Set.of(200)));
            var reply=small.lookup("key");assertEquals(Outcome.CONFIRMED,reply.outcome());
            assertFalse(reply.detail().contains("private response"));
            var target=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),Set.of(),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false,
                    new PinnedHttpEffectTarget.Lookup(uri(server,"/lookup"),Set.of(200)));
            assertEquals(Outcome.UNKNOWN,target.lookup("key").outcome());
        } finally {server.stop(0);}
    }
    @Test void timeoutIsUnknownAndNeverARefusalOrSuccess() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var entered=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/slow",exchange->{entered.countDown();try {Thread.sleep(700);exchange.sendResponseHeaders(200,-1);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}catch(java.io.IOException ignored){} finally {exchange.close();}});server.start();
        try {
            long start=System.nanoTime();var reply=operation(uri(server,"/slow"),Duration.ofMillis(200)).execute("key");
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(Outcome.UNKNOWN,reply.outcome());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(4))<0);
        } finally {server.stop(0);executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);}
    }
    @Test void unresolvedCallsReserveSharedCapacityAcrossTargets() throws Exception { runProbe(SaturationProbe.class); }
    @Test void blockedCredentialAcquisitionSharesCapacityAcrossTargets() throws Exception { runProbe(ProviderSaturationProbe.class); }
    @Test void completedUnknownLookupsDoNotExhaustCapacity() throws Exception { runProbe(LookupCompletionProbe.class); }
    @Test void unfinishedLookupBodiesRemainCapacityBounded() throws Exception { runProbe(LookupTimeoutProbe.class); }
    private static void runProbe(Class<?> probe) throws Exception {
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var arguments=java.nio.file.Files.createTempFile("http-capacity-", ".args");
        String escaped=classpath.replace("\\","\\\\").replace("\"","\\\"");
        java.nio.file.Files.writeString(arguments,"-cp\n\""+escaped+"\"\n"+probe.getName()+"\n",StandardCharsets.UTF_8);
        Process child=null;
        try {
            child=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "@"+arguments.toAbsolutePath()).redirectErrorStream(true).start();
            assertTrue(child.waitFor(25,TimeUnit.SECONDS),"capacity probe timed out");
            String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertEquals(0,child.exitValue(),output);
        } finally {
            if(child!=null && child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
            java.nio.file.Files.deleteIfExists(arguments);
        }
    }

    /** A fresh JVM keeps intentional quarantines isolated from other tests. */
    public static final class SaturationProbe {
        public static void main(String[] args) throws Exception {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
            server.createContext("/orders",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
            try {
                URI endpoint=uri(server,"/orders");
                for(int i=0;i<PinnedHttpEffectTarget.CAPACITY+1;i++) {
                    var target=operation(endpoint,TIMEOUT);
                    if(target.execute("key-"+i).outcome()!=Outcome.UNKNOWN) throw new AssertionError("unresolved call was accepted");
                }
                if(calls.get()!=PinnedHttpEffectTarget.CAPACITY) throw new AssertionError("shared capacity was not retained: "+calls.get());
            } finally {server.stop(0);}
        }
    }

    public static final class ProviderSaturationProbe {
        public static void main(String[] args) throws Exception {
            var entered=new CountDownLatch(PinnedHttpEffectTarget.CAPACITY);var release=new CountDownLatch(1);
            var seen=new AtomicInteger();var endpoint=URI.create("http://127.0.0.1:1/orders");
            try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
                for(int i=0;i<PinnedHttpEffectTarget.CAPACITY;i++) workers.submit(()->{
                    var target=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                            Set.of(200),EXPECTED,(a,action)->{seen.incrementAndGet();entered.countDown();release.await();return action.run(token(a));},
                            issued->{throw new IllegalStateException("stop before send");},true);
                    try{target.execute("key");}catch(Exception expected){}
                });
                try {
                    if(!entered.await(10,TimeUnit.SECONDS))throw new AssertionError("providers did not enter");
                    var extra=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                            Set.of(200),EXPECTED,(a,action)->{seen.incrementAndGet();return action.run(token(a));},issued->{},true);
                    if(extra.execute("extra").outcome()!=Outcome.UNKNOWN || seen.get()!=PinnedHttpEffectTarget.CAPACITY)
                        throw new AssertionError("capacity did not bound credential acquisition");
                } finally {release.countDown();}
            }
        }
    }

    public static final class LookupCompletionProbe {
        public static void main(String[] args) throws Exception {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
            server.createContext("/lookup",exchange->{try {int n=calls.incrementAndGet();
                exchange.sendResponseHeaders(n<=PinnedHttpEffectTarget.CAPACITY*2 ? 404 : 200,-1);}finally{exchange.close();}});
            server.start();
            try {
                var target=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",TIMEOUT,
                        Set.of(200),Set.of(),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false,
                        new PinnedHttpEffectTarget.Lookup(uri(server,"/lookup"),Set.of(200)));
                for(int i=0;i<PinnedHttpEffectTarget.CAPACITY*2;i++)
                    if(target.lookup("key").outcome()!=Outcome.UNKNOWN)throw new AssertionError("404 should remain unknown");
                if(target.lookup("key").outcome()!=Outcome.CONFIRMED || calls.get()!=PinnedHttpEffectTarget.CAPACITY*2+1)
                    throw new AssertionError("completed lookup responses exhausted capacity: "+calls.get());
            } finally {server.stop(0);}
        }
    }

    public static final class LookupTimeoutProbe {
        public static void main(String[] args) throws Exception {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
            var release=new CountDownLatch(1);var workers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(workers);
            server.createContext("/lookup",exchange->{try {calls.incrementAndGet();exchange.sendResponseHeaders(200,1);
                release.await();exchange.getResponseBody().write(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                catch(java.io.IOException ignored){}finally{exchange.close();}});server.start();
            try {
                var target=new PinnedHttpEffectTarget("tool:orders",uri(server,"/orders"),"audience:orders","trace-1","{}",Duration.ofMillis(350),
                        Set.of(200),Set.of(),EXPECTED,(a,action)->action.run(token(a)),issued->{},true,false,
                        new PinnedHttpEffectTarget.Lookup(uri(server,"/lookup"),Set.of(200)));
                for(int i=0;i<PinnedHttpEffectTarget.CAPACITY+1;i++)
                    if(target.lookup("key").outcome()!=Outcome.UNKNOWN)throw new AssertionError("incomplete response was accepted");
                if(calls.get()!=PinnedHttpEffectTarget.CAPACITY)throw new AssertionError("unfinished lookup capacity was not retained: "+calls.get());
            } finally {release.countDown();server.stop(0);workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }

    @Test void lateOrMismatchedCredentialsAndDeniedFenceCannotSend() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/orders",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {
            URI endpoint=uri(server,"/orders");
            var expired=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),EXPECTED,(a,action)->action.run(new IssuedToken(Credential.of("token"),a,Set.of("orders:write"),
                    Instant.now().minusSeconds(1),"issuer","jti",List.of("actor"),GRANT)),issued->{},true);
            assertThrows(IllegalStateException.class,()->expired.execute("key"));
            var wrong=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),EXPECTED,(a,action)->action.run(token("wrong-audience")),issued->{},true);
            assertThrows(IllegalStateException.class,()->wrong.execute("key"));
            for(var invalid:List.of(
                    new IssuedToken(Credential.of("token"),"audience:orders",Set.of("orders:write"),Instant.now().plusSeconds(60),"other","jti",List.of("actor"),GRANT),
                    new IssuedToken(Credential.of("token"),"audience:orders",Set.of("orders:write"),Instant.now().plusSeconds(60),"issuer","jti",List.of("actor"),UlidGenerator.shared().next()),
                    new IssuedToken(Credential.of("token"),"audience:orders",Set.of("orders:write","extra"),Instant.now().plusSeconds(60),"issuer","jti",List.of("actor"),GRANT))) {
                var mismatch=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                        Set.of(200),EXPECTED,(a,action)->action.run(invalid),issued->{},true);
                assertThrows(IllegalStateException.class,()->mismatch.execute("key"));
            }
            var fenceEntered=new AtomicBoolean();
            var expiresDuringFence=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),EXPECTED,(a,action)->action.run(new IssuedToken(Credential.of("token"),a,Set.of("orders:write"),
                    Instant.now().plusSeconds(2),"issuer","jti",List.of("actor"),GRANT)),issued->{fenceEntered.set(true);Thread.sleep(2100);},true);
            assertThrows(IllegalStateException.class,()->expiresDuringFence.execute("key"));
            assertTrue(fenceEntered.get(),"token must be current when the blocking fence begins");
            var denied=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",TIMEOUT,
                    Set.of(200),EXPECTED,(a,action)->action.run(token(a)),issued->{throw new IllegalStateException("denied");},true);
            assertThrows(IllegalStateException.class,()->denied.execute("key"));
            var slow=new PinnedHttpEffectTarget("tool:orders",endpoint,"audience:orders","trace-1","{}",Duration.ofMillis(100),
                    Set.of(200),EXPECTED,(a,action)->{Thread.sleep(200);return action.run(token(a));},issued->{},true);
            assertEquals(Outcome.UNKNOWN,slow.execute("key").outcome());assertEquals(0,calls.get());
        } finally {server.stop(0);}
    }

    @Test void rejectsUntrustedConfigurationAndInvalidCredentialsBeforeSending() {
        URI secure=URI.create("https://example.test/orders");
        assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",URI.create("http://example.test/x"),"aud","c","{}",TIMEOUT,Set.of(200),EXPECTED,(a,action)->action.run(token(a)),issued->{}));
        assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",URI.create("https://example.test/x?redirect=other"),"aud","c","{}",TIMEOUT,Set.of(200),EXPECTED,(a,action)->action.run(token(a)),issued->{}));
        assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",secure,"aud","c","{}",TIMEOUT,Set.of(202),EXPECTED,(a,action)->action.run(token(a)),issued->{}));
        assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",secure,"aud","c","{",TIMEOUT,Set.of(200),EXPECTED,(a,action)->action.run(token(a)),issued->{}));
        assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",secure,"aud","c","{\"x\":1,\"x\":2}",TIMEOUT,Set.of(200),EXPECTED,(a,action)->action.run(token(a)),issued->{}));
        assertThrows(IllegalArgumentException.class,()->new PinnedHttpEffectTarget("tool:orders",secure,"aud","c","{} {}",TIMEOUT,Set.of(200),EXPECTED,(a,action)->action.run(token(a)),issued->{}));
        var target=new PinnedHttpEffectTarget("tool:orders",secure,"aud","c","{}",TIMEOUT,Set.of(200),EXPECTED,
                (a,action)->action.run(new IssuedToken(Credential.of("bad\r\nheader"),a,Set.of("orders:write"),Instant.now().plusSeconds(60),
                        "issuer","jti",List.of("actor"),GRANT)),issued->{});
        assertThrows(IllegalStateException.class,()->target.execute("key"));
        assertThrows(IllegalArgumentException.class,()->target.execute("bad\r\nkey"));
    }
}
