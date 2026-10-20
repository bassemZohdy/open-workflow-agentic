package io.github.bassemzohdy.openworkflow.agentic.event;

import static io.restassured.RestAssured.given;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.identity.FakeIdpResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.StructuredEvent;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(EventIngressUnavailablePolicyTest.Profile.class)
@QuarkusTestResource(value = FakeIdpResource.class, restrictToAnnotatedClass = true)
class EventIngressUnavailablePolicyTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() { return Map.of("agentic.events.policy-folder", "policy/does-not-exist"); }
    }
    @Test
    void policyUnavailableNeverUsesSafeRecoveryForEventIngress() throws Exception {
        given().auth().oauth2(EventIngressApiTest.token("alice", EventIngressApiTest.TENANT, "oa.events.receive"))
                .contentType(StructuredEvent.CONTENT_TYPE).body(EventIngressApiTest.event("one", "urn:test:orders", "{}"))
                .post(EventIngressApiTest.path(UlidGenerator.shared().next().value(), "wait-1")).then().statusCode(503);
    }
}
