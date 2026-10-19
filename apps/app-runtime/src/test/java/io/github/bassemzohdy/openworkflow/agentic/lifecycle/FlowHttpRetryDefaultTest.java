package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.get;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * The control for {@link FlowStatusSyncTest#aFailedEffectIsRecordedAsFailedAndTheCallIsNotRetriedByTheClient}: with the
 * Flow client's own retries put back to their default of three, the same failing effect call reaches the endpoint more
 * than once. That shows the property {@code quarkus.flow.http.client.resilience.retry.max-retries=0} has an effect
 * (WP-003, Q08).
 */
@QuarkusTest
@TestProfile(FlowHttpRetryDefaultProfile.class)
class FlowHttpRetryDefaultTest {

    @TestHTTPResource("/dev/lifecycle/effects")
    URL effectUrl;

    @Test
    void withTheDefaultClientRetriesTheFailingCallReachesTheEndpointMoreThanOnce() throws Exception {
        String tenant = newTenant();
        String id = FlowStatusSyncTest.start(tenant, "fail", effectUrl);
        FlowStatusSyncTest.awaitStatus(tenant, id, "FAILED");
        String engineId = get(tenant, id).extract().path("engineInstanceId");

        int calls = FlowStatusSyncTest.callsFor(engineId, "fail");

        assertTrue(calls > 1, "the default client retry must repeat the call, saw " + calls);
    }
}
