package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceInvocations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceCallBindings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Canonical DATA storage stays available; production admission still needs the reviewed execution gate. */
@QuarkusTest
@TestProfile(ResourceBindingsDefaultWiringTest.WithoutAdmissionOverride.class)
class ResourceBindingsDefaultWiringTest {
    public static class WithoutAdmissionOverride implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("quarkus.arc.exclude-types",
                    "io.github.bassemzohdy.openworkflow.agentic.identity.InMemoryResourceCallBindings");
        }
    }
    @Inject JdbcResourceCallBindings concrete;
    @Inject ResourceInvocations canonical;
    @Inject Instance<ResourceCallBindings> admission;

    @Test void canonicalStorageIsAvailableWhileProductionAdmissionRemainsUnwired() {
        assertInstanceOf(JdbcResourceCallBindings.class,concrete);
        assertInstanceOf(JdbcResourceCallBindings.class,canonical);
        assertTrue(admission.isUnsatisfied(),"historical attribution must not activate the unguarded admission consumer");
    }
}
