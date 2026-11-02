package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(StartCapacityApiTest.Profile.class)
class StartCapacityApiTest {
    public static final class Profile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() {
            return Map.of("agentic.dev.trust-caller-headers", "true", "agentic.reconciler.enabled", "false",
                    "agentic.lifecycle.max-concurrent-starts", "4", "quarkus.datasource.jdbc.max-size", "2");
        }
    }
    @Inject CountingWorkflowEngine engine;

    @Test void saturationRejectsNewStartsButAllowsIdempotentReadsAndRetry() throws Exception {
        engine.reset(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        engine.holdNextPrepare(entered, release);
        String tenant = newTenant(), firstKey = newKey(), secondKey = newKey();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> post(tenant, standardBody(firstKey)).extract().response());
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            post(tenant, standardBody(firstKey)).statusCode(200);
            var busy = post(tenant, standardBody(secondKey)).statusCode(503).extract().response();
            assertTrue(busy.asString().contains("retry with the same idempotency key"));
            release.countDown();
            assertEquals(202, first.get(30, TimeUnit.SECONDS).statusCode());
            post(tenant, standardBody(secondKey)).statusCode(202);
            assertEquals(2, engine.starts());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
