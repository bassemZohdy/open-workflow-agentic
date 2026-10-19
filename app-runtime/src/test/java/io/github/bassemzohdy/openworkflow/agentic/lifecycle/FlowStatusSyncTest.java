package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.get;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.post;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The real Quarkus Flow engine reports its own statuses into the registry (WP-105c, scope item 3): WAITING when the
 * listen is registered, COMPLETED when the event arrives, FAILED when an effect call fails. The reconciler is off
 * here; these changes come from the observer alone.
 */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class FlowStatusSyncTest {

    @TestHTTPResource("/dev/lifecycle/effects")
    URL effectUrl;

    static final String PROBE = "agentic/lifecycle-probe";

    static String start(String tenant, String mode, URL effect) {
        Map<String, Object> input = Map.of("effectUrl", effect.toString(), "mode", mode);
        return post(tenant, LifecycleSupport.body(PROBE, "0.1.0", newKey(), input))
                .statusCode(202)
                .extract().path("id");
    }

    /** Polls the registry until the status is one of the expected ones, or fails after 30 seconds. */
    static String awaitStatus(String tenant, String id, String... expected) throws InterruptedException {
        String last = "";
        for (int i = 0; i < 300; i++) {
            last = get(tenant, id).statusCode(200).extract().path("status");
            if (Arrays.asList(expected).contains(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        return fail("status " + last + " never became one of " + Arrays.toString(expected));
    }

    @Test
    void theRegistryFollowsTheEngineThroughWaitingToCompleted() throws Exception {
        String tenant = newTenant();
        String id = start(tenant, "ok", effectUrl);

        awaitStatus(tenant, id, "WAITING");
        String engineId = get(tenant, id).extract().path("engineInstanceId");
        assertNotNull(engineId);

        // Publishing after the listen is registered: the event wakes the instance (WP-003, Q02).
        given().contentType("application/json").body(Map.of("type", "org.openworkflow.agentic.lifecycle.probe"))
                .when().post("/dev/lifecycle/events").then().statusCode(202);

        awaitStatus(tenant, id, "COMPLETED");
    }

    @Test
    void aFailedEffectIsRecordedAsFailedAndTheCallIsNotRetriedByTheClient() throws Exception {
        String tenant = newTenant();
        String id = start(tenant, "fail", effectUrl);

        awaitStatus(tenant, id, "FAILED");
        String engineId = get(tenant, id).extract().path("engineInstanceId");

        // Default retries are off (quarkus.flow.http.client.resilience.retry.max-retries=0, ADR 0019): one call.
        assertEquals(1, callsFor(engineId, "fail"));
    }

    static int callsFor(String engineId, String mode) {
        List<Map<String, Object>> calls = given().when().get("/dev/lifecycle/effects").then().statusCode(200)
                .extract().jsonPath().getList("$");
        return (int) calls.stream()
                .filter(call -> engineId.equals(call.get("instanceId")) && mode.equals(call.get("mode")))
                .count();
    }

}
