package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Trusted caller headers, and Flow's default HTTP client retries (three), to measure what the default does. */
public class FlowHttpRetryDefaultProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "agentic.dev.trust-caller-headers", "true",
                "quarkus.flow.http.client.resilience.retry.max-retries", "3");
    }
}
