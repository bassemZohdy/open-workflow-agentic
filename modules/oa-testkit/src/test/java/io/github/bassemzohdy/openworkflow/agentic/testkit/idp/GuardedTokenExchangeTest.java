package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.ALICE;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_ID;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_SECRET_NAME;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.PLANNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException.Code;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.resilience.DependencyGuard;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.DelegationAuthorizationServer.Fault;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The token exchange through a DependencyGuard: an outage opens the circuit, a refusal never does, nothing is retried. */
class GuardedTokenExchangeTest {

    private static final String AUDIENCE = "tool-cases";
    private static final Set<String> SCOPES = Set.of("cases:read");

    private IdentityFixture fx;
    private DependencyGuard guard;
    private Rfc8693TokenExchanger exchanger;
    private DelegationGrant grant;
    private String userToken;
    private String workload;

    @BeforeEach
    void start() throws Exception {
        fx = new IdentityFixture(AUDIENCE, "tool-search", CLIENT_ID);
        guard = new DependencyGuard(new DependencyGuard.Settings("token-endpoint", 4, 3, Duration.ofSeconds(30), 0,
                Duration.ofMinutes(1), Duration.ZERO), fx.clock);
        exchanger = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets, fx.grants,
                fx.bindings, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2), guard);
        grant = fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, SCOPES, fx.clock.instant().plus(Duration.ofHours(1)),
                "corr-1");
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofMinutes(30));
        workload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
    }

    @AfterEach
    void stop() {
        if (exchanger != null) exchanger.close();
        if (fx != null) fx.close();
    }

    private ExchangeRequest request(String audience) {
        return new ExchangeRequest(Credential.of(userToken), Credential.of(workload), audience, SCOPES, grant);
    }

    private ExchangeException failed(String audience) {
        return assertThrows(ExchangeException.class, () -> exchanger.exchange(request(audience)));
    }

    @Test
    void aHealthyExchangeThroughTheGuardIssuesTheSameTokenAndCountsASuccess() {
        var token = exchanger.exchange(request(AUDIENCE));
        assertEquals(AUDIENCE, token.audience());
        assertEquals(1, guard.stats().successes());
        assertEquals(DependencyGuard.State.CLOSED, guard.state());
    }

    @Test
    void consecutiveOutagesOpenTheCircuitAndTheServerIsNotAskedAgain() {
        fx.as.fault(Fault.UNAVAILABLE);
        for (int i = 0; i < 3; i++) assertEquals(Code.UNAVAILABLE, failed(AUDIENCE).code());
        assertEquals(DependencyGuard.State.OPEN, guard.state());
        int received = fx.as.received().size();

        var shed = failed(AUDIENCE);

        assertEquals(Code.UNAVAILABLE, shed.code(), "the caller still sees the typed unavailable outcome");
        assertTrue(shed.getMessage().contains("CIRCUIT_OPEN"), shed.getMessage());
        assertEquals(received, fx.as.received().size(), "an open circuit sends nothing to the server");
    }

    @Test
    void aRefusalIsTheServerWorkingAndNeverOpensTheCircuit() {
        for (int i = 0; i < 6; i++) assertEquals(Code.REFUSED, failed("tool-unregistered").code());
        assertEquals(DependencyGuard.State.CLOSED, guard.state());
        assertEquals(6, guard.stats().successes());
        assertEquals(0, guard.stats().failures());
        assertEquals(AUDIENCE, exchanger.exchange(request(AUDIENCE)).audience(), "exchanges still work");
    }

    @Test
    void theCircuitClosesAgainOnceTheServerRecoversAndTheProbeSucceeds() {
        fx.as.fault(Fault.UNAVAILABLE);
        for (int i = 0; i < 3; i++) failed(AUDIENCE);
        fx.as.fault(Fault.NONE);
        fx.clock.advance(Duration.ofSeconds(30));

        assertEquals(AUDIENCE, exchanger.exchange(request(AUDIENCE)).audience());
        assertEquals(DependencyGuard.State.CLOSED, guard.state());
    }

    @Test
    void anUnreachableServerCountsAsAnOutageToo() {
        var dead = new Rfc8693TokenExchanger("http://127.0.0.1:1/token", CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets,
                fx.grants, fx.bindings, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(1), guard);
        for (int i = 0; i < 3; i++) {
            assertEquals(Code.UNAVAILABLE, assertThrows(ExchangeException.class, () -> dead.exchange(request(AUDIENCE))).code());
        }
        assertEquals(DependencyGuard.State.OPEN, guard.state());
    }

    @Test
    void anExchangerWithoutAGuardBehavesAsBefore() {
        var plain = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets, fx.grants,
                fx.bindings, fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2));
        fx.as.fault(Fault.UNAVAILABLE);
        for (int i = 0; i < 5; i++) {
            assertEquals(Code.UNAVAILABLE, assertThrows(ExchangeException.class, () -> plain.exchange(request(AUDIENCE))).code());
        }
        assertEquals(5, fx.as.received().size(), "every request reaches the server when there is no guard");
    }
    @Test
    void stalledResponseBodyTimesOutWithoutIssuingOrRetrying() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            received.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var stalled = new Rfc8693TokenExchanger("http://127.0.0.1:" + server.getAddress().getPort() + "/token",
                CLIENT_ID, CLIENT_SECRET_NAME, fx.secrets, fx.grants, fx.bindings, fx.verifier, fx.audit(), fx.clock,
                Duration.ofSeconds(1), guard, 1)) {
            long start = System.nanoTime();
            var failure = assertThrows(ExchangeException.class, () -> stalled.exchange(request(AUDIENCE)));
            assertEquals(Code.UNAVAILABLE, failure.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
            assertEquals(1, received.get(), "a timed-out issuance must never be retried");
            assertTrue(fx.sink.byAction(Rfc8693TokenExchanger.ISSUED).isEmpty());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void productionProducerWiresTheConfiguredCircuitWithoutRetryingIssuance() throws Exception {
        fx.clock.setInstant(java.time.Instant.now());
        grant = fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, SCOPES, fx.clock.instant().plus(Duration.ofHours(1)),
                "production-guard");
        userToken = fx.as.userToken("alice", fx.tenant, "oa-api", SCOPES, Duration.ofMinutes(30));
        workload = fx.as.workloadToken("planner", fx.tenant, Duration.ofMinutes(30));
        var settings = new io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentitySettings.Exchange() {
            public java.util.Optional<String> clientId() { return java.util.Optional.of(CLIENT_ID); }
            public java.util.Optional<String> clientSecretName() { return java.util.Optional.of(CLIENT_SECRET_NAME); }
            public Duration timeout() { return Duration.ofSeconds(2); }
            public int maxConcurrent() { return 4; }
            public boolean circuitEnabled() { return true; }
            public int circuitFailureThreshold() { return 2; }
            public Duration circuitOpenFor() { return Duration.ofMinutes(1); }
        };
        var mapping = (io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentitySettings)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentitySettings.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "tokenEndpoint" -> java.util.Optional.of(fx.as.tokenEndpoint());
                            case "issuer" -> java.util.Optional.of(fx.idp.issuer());
                            case "exchange" -> settings;
                            default -> throw new AssertionError("unexpected setting: " + method.getName());
                        });
        var producer = new io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityProducers();
        var field = producer.getClass().getDeclaredField("settings");
        field.setAccessible(true);
        field.set(producer, mapping);
        var produced = producer.tokenExchanger(fx.secrets, fx.grants, fx.bindings, fx.verifier, fx.audit());
        try {
            fx.as.fault(Fault.UNAVAILABLE);
            for (int i = 0; i < 2; i++) {
                assertEquals(Code.UNAVAILABLE, assertThrows(ExchangeException.class,
                        () -> produced.exchange(request(AUDIENCE))).code());
            }
            assertEquals(2, fx.as.received().size());
            var rejected = assertThrows(ExchangeException.class, () -> produced.exchange(request(AUDIENCE)));
            assertEquals(Code.UNAVAILABLE, rejected.code());
            assertTrue(rejected.getMessage().contains("CIRCUIT_OPEN"));
            assertEquals(2, fx.as.received().size(), "the open circuit sends no request or retry");
        } finally {
            producer.disposeTokenExchanger(produced);
        }
    }
}
