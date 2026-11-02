package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.http;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
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
                    Duration.ofMillis(500),Set.of(200),expected,audience->new IssuedToken(Credential.of("token"),audience,
                    Set.of("orders:write"),Instant.now().plusSeconds(60),"issuer","jti",List.of("actor"),grant),issued->{},true);
            var thread=ThreadId.derive("http-effect-test","alice",UUID.randomUUID().toString());
            var command=EffectCommand.of(thread,"send","occurrence-1","tool:orders","{\"amount\":10}");
            assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
            assertEquals(1,calls.get(),"a zero replay window must not send the unresolved effect again");
            assertEquals(1,ledger.manualQueue(thread,10).size());
        } finally {server.stop(0);executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);}
    }
}
