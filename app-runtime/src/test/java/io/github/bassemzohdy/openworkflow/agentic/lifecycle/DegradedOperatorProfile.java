package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Trusted development headers, and an operations policy folder that does not exist. The policy engine therefore never
 * loads a snapshot, so every decision is ERROR, and only the safe-recovery rule (cancel and pause with the recovery scope)
 * can allow anything.
 */
public class DegradedOperatorProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "agentic.dev.trust-caller-headers", "true",
                "agentic.operations.policy-folder", "policy/no-such-snapshot");
    }
}
