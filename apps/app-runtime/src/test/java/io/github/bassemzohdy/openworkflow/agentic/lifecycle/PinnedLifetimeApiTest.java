package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.*;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The real application start path pins the configured limit in the same registry transaction. */
@QuarkusTest
@TestProfile(PinnedLifetimeApiTest.Profile.class)
class PinnedLifetimeApiTest {
    public static final class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("agentic.dev.trust-caller-headers", "true", "agentic.reconciler.enabled", "false",
                    "agentic.reconciler.max-lifetime", "PT10M");
        }
    }
    @Inject JdbcInstanceStore store;
    @Inject RegistryScan scan;
    @Inject RepairStore repairs;
    @Inject EngineStatusSync sync;
    @Inject DataSource dataSource;

    @Test void realStartPinsItsLimitAndAFreshReconcilerEnforcesItWithoutTheSetting() {
        String tenant = newTenant(); String key = newKey();
        String id = post(tenant, standardBody(key)).statusCode(202).extract().path("id");
        var row = store.find(tenant, Ulid.parse(id)).orElseThrow();
        assertEquals(row.createdAt().plus(Duration.ofMinutes(10)), scan.pinnedLifetime(id).orElseThrow());
        post(tenant, standardBody(key)).statusCode(200);
        assertEquals(row.createdAt().plus(Duration.ofMinutes(10)), scan.pinnedLifetime(id).orElseThrow());
        var engine = new FakeEngine(); engine.report(row.engineInstanceId(), EngineStatus.RUNNING);
        var reconciler = new Reconciler(store, engine, sync, scan, repairs, LifecycleSettings.defaults(), dataSource,
                Clock.fixed(row.createdAt().plus(Duration.ofMinutes(11)), ZoneOffset.UTC), tenant::equals);
        reconciler.recover(tenant, id);
        assertEquals(InstanceStatus.CANCEL_REQUESTED, store.find(tenant, Ulid.parse(id)).orElseThrow().status());
        assertEquals(1, engine.cancels.get());
    }
}
