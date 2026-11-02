package io.github.bassemzohdy.openworkflow.agentic.event;

import static io.restassured.RestAssured.given;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.StructuredEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(EventIngressDevHeadersTest.Profile.class)
class EventIngressDevHeadersTest {
    public static final class Profile extends EventIngressApiTest.Profile {
        @Override public Map<String, String> getConfigOverrides() {
            var config = new HashMap<>(super.getConfigOverrides());
            config.put("agentic.dev.trust-caller-headers", "true");
            return Map.copyOf(config);
        }
    }
    @Inject InstanceStore store;
    @Test void explicitlyEnabledDevelopmentHeadersStillUseTheEventPolicyAndProducerBinding() {
        String tenant = EventIngressApiTest.TENANT;
        var start = new StartRequest(tenant, new Subject(SubjectKind.USER, "alice"),
                new DefinitionRef("agentic/orders", "1"), Map.of(), UUID.randomUUID().toString());
        var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), start, Instant.now()), "test.start").record();
        given().header("X-Tenant", tenant).header("X-Subject", "alice").header("X-Scopes", "oa.events.receive")
                .contentType(StructuredEvent.CONTENT_TYPE)
                .body(EventIngressApiTest.event(UUID.randomUUID().toString(), "urn:test:orders", "{}"))
                .post(EventIngressApiTest.path(row.id().value(), "wait-1")).then().statusCode(202);
    }
}
