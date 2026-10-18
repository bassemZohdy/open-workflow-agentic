package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Development headers are trusted, so tests can act as a tenant and a subject. */
public class TrustedCallerProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("agentic.dev.trust-caller-headers", "true");
    }
}
