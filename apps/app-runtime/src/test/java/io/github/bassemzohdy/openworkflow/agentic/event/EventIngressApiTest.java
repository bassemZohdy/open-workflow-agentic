package io.github.bassemzohdy.openworkflow.agentic.event;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.identity.FakeIdpResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.StructuredEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real HTTP, product JWT verification, Cedar PEP, durable audit and PostgreSQL inbox; issuer is a test double. */
@QuarkusTest
@TestProfile(EventIngressApiTest.Profile.class)
@QuarkusTestResource(value = FakeIdpResource.class, restrictToAnnotatedClass = true)
class EventIngressApiTest {
    static final String TENANT = "event-api";
    public static class Profile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() {
            return Map.of("agentic.events.producers.orders.tenant", TENANT,
                    "agentic.events.producers.orders.kind", "USER",
                    "agentic.events.producers.orders.subject", "alice",
                    "agentic.events.producers.orders.source", "urn:test:orders",
                    "agentic.events.producers.orders.types", "order.ready",
                    "agentic.events.producers.orders.definitions", "agentic/orders",
                    "agentic.events.producers.orders.correlations", "wait-1");
        }
    }
    @Inject InstanceStore store;
    @Inject DataSource dataSource;

    String instance(String definition) {
        var request = new StartRequest(TENANT, new Subject(SubjectKind.USER, "alice"), new DefinitionRef(definition, "1"),
                Map.of(), "event-api-" + UUID.randomUUID());
        return store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)), "test.event-start").record().id().value();
    }
    static String token(String subject, String tenant, String scope) throws Exception {
        var claims = FakeIdpResource.idp().baseClaims(subject, null, Duration.ofMinutes(5));
        claims.put("aud", FakeIdpResource.AUDIENCE); claims.put("tenant", tenant); claims.put("scope", scope);
        return FakeIdpResource.idp().signClaims(claims);
    }
    static String event(String id, String source, String data) {
        return "{\"specversion\":\"1.0\",\"source\":\"" + source + "\",\"id\":\"" + id
                + "\",\"type\":\"order.ready\",\"tenant\":\"untrusted-other\",\"data\":" + data + "}";
    }
    static String path(String instance, String correlation) { return "/instances/" + instance + "/events/" + correlation; }
    long count(String table, String instance) throws Exception {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("select count(*) from " + table + " where instance_id=?")) {
            s.setString(1, instance); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    @Test
    void verifiesBearerCallerAuditsAndPersistsBeforeAnyWaitAndCanonicalRetryReturnsSameReceipt() throws Exception {
        String instance = instance("agentic/orders"), id = UUID.randomUUID().toString();
        String jwt = token("alice", TENANT, "oa.events.receive");
        String first = event(id, "urn:test:orders", "{\"z\":2,\"a\":1}");
        given().auth().oauth2(jwt).header("X-Tenant", "forged").header("X-Subject", "mallory")
                .contentType(StructuredEvent.CONTENT_TYPE).body(first).post(path(instance, "wait-1"))
                .then().statusCode(202).body("id", equalTo(id)).body("outcome", equalTo("received"));
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE)
                .body(event(id, "urn:test:orders", "{\"a\":1,\"z\":2}")).post(path(instance, "wait-1"))
                .then().statusCode(200);
        assertEquals(1, count("inbox.message", instance)); assertEquals(0, count("inbox.signal", instance));
        try (var c = dataSource.getConnection(); var s = c.prepareStatement(
                "select tenant from inbox.message where instance_id=?")) {
            s.setString(1, instance); try (var r = s.executeQuery()) { assertTrue(r.next()); assertEquals(TENANT, r.getString(1)); }
        }
        assertTrue(count("audit.event", instance) >= 4, "attempt/result audit records bind the authenticated caller to the instance");
    }

    @Test
    void refusesAnonymousForgedMissingScopeWrongTenantSourceDefinitionAndCorrelation() throws Exception {
        String instance = instance("agentic/orders"), body = event(UUID.randomUUID().toString(), "urn:test:orders", "{}");
        given().header("X-Tenant", TENANT).header("X-Subject", "alice").header("X-Scopes", "oa.events.receive")
                .contentType(StructuredEvent.CONTENT_TYPE).body(body).post(path(instance, "wait-1")).then().statusCode(401);
        given().auth().oauth2(token("mallory", TENANT, "oa.events.receive")).header("X-Subject", "alice")
                .contentType(StructuredEvent.CONTENT_TYPE).body(body).post(path(instance, "wait-1")).then().statusCode(403);
        given().auth().oauth2(token("alice", TENANT, "profile")).header("X-Scopes", "oa.events.receive")
                .contentType(StructuredEvent.CONTENT_TYPE).body(body).post(path(instance, "wait-1")).then().statusCode(403);
        given().auth().oauth2(token("alice", "other-tenant", "oa.events.receive"))
                .contentType(StructuredEvent.CONTENT_TYPE).body(body).post(path(instance, "wait-1")).then().statusCode(404);
        String jwt = token("alice", TENANT, "oa.events.receive");
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE)
                .body(event(UUID.randomUUID().toString(), "urn:test:spoofed", "{}"))
                .post(path(instance, "wait-1")).then().statusCode(403);
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE).body(body)
                .post(path(instance, "wrong-wait")).then().statusCode(403);
        String wrongDefinition = instance("agentic/other");
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE).body(body)
                .post(path(wrongDefinition, "wait-1")).then().statusCode(403);
        assertEquals(0, count("inbox.message", instance)); assertEquals(0, count("inbox.message", wrongDefinition));
    }

    @Test
    void changedPayloadOrTargetConflictsAndOversizedOrMalformedEventNeverPersists() throws Exception {
        String instance = instance("agentic/orders"), another = instance("agentic/orders"), id = UUID.randomUUID().toString();
        String jwt = token("alice", TENANT, "oa.events.receive"), body = event(id, "urn:test:orders", "{}");
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE).body(body).post(path(instance, "wait-1")).then().statusCode(202);
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE).body(event(id, "urn:test:orders", "{\"changed\":true}"))
                .post(path(instance, "wait-1")).then().statusCode(409);
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE).body(body).post(path(another, "wait-1")).then().statusCode(409);
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE).body("{}{}").post(path(another, "wait-1")).then().statusCode(400);
        given().auth().oauth2(jwt).contentType(StructuredEvent.CONTENT_TYPE).body(event("huge", "urn:test:orders", "\"" + "x".repeat(65536) + "\""))
                .post(path(another, "wait-1")).then().statusCode(413);
        assertEquals(1, count("inbox.message", instance)); assertEquals(0, count("inbox.message", another));
    }
}
