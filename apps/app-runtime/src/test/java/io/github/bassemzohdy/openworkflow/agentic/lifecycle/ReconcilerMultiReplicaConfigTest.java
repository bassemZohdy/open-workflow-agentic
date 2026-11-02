package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.LifecycleSettings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ReconcilerMultiReplicaConfigTest.Profile.class)
class ReconcilerMultiReplicaConfigTest {
    public static final class Profile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("agentic.reconciler.multi-replica", "true");
        }
    }

    @Inject LifecycleSettings settings;

    @Test void deploymentSettingReachesTheInjectedReconcilerSettings() {
        assertTrue(settings.multiReplica);
    }
}
