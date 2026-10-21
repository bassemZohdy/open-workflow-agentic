package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceInvocations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceCallBindings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Actual CDI production-default bridge; existing delegated HTTP tests retain their separate port double. */
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
    @Inject ResourceCallBindings admission;

    @Test void absentAdmissionOverrideUsesTheDurableStoreAndKeepsCanonicalLookupAvailable() {
        assertInstanceOf(JdbcResourceCallBindings.class,concrete);
        assertInstanceOf(JdbcResourceCallBindings.class,canonical);
        assertInstanceOf(JdbcResourceCallBindings.class,admission);
    }
}
