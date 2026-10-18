package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.standardBody;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * The real Quarkus Flow engine executes the definition shipped in {@code src/main/resources/flow}. No test engine
 * replaces it: the counting engine is configured without failures, so this call reaches {@code definition.instance}
 * and {@code start()} of Flow 1.2.0 and returns the engine's own instance id.
 */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class RealFlowStartTest {

    @Test
    void aShippedDefinitionStartsOnTheRealEngineAndTheRegistryRecordsItsId() {
        post(newTenant(), standardBody(newKey()))
                .statusCode(202)
                .body("status", equalTo("RUNNING"))
                .body("engineInstanceId", matchesPattern("[0-7][0-9A-HJKMNP-TV-Z]{25}"));
    }
}
