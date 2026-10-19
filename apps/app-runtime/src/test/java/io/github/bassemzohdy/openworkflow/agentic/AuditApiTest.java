package io.github.bassemzohdy.openworkflow.agentic;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditRecorder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditVerificationJob;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.VersionStamps;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The audit query API and verification on the running application, with the development flag on (WP-111). */
@QuarkusTest
@TestProfile(AuditOpenProfile.class)
class AuditApiTest {

    private static final Subject AGENT = new Subject(SubjectKind.AGENT, "research-agent");

    /** A request from an identified development caller (the header seam is on in this profile). */
    private static io.restassured.specification.RequestSpecification caller() {
        return given().header("X-Tenant", "audit-test").header("X-Subject", "auditor");
    }

    @Inject
    AuditRecorder recorder;

    @Inject
    AuditVerificationJob verificationJob;

    @Test
    void eventsOfAnInstancePageWithAnOpaqueCursor() {
        String instance = "wf-" + UUID.randomUUID();
        String chain = "api-" + UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            recorder.record(AuditRequest.of(AGENT, "step." + i, "run", "run-1").inChain(chain).forInstance(instance)
                    .withCorrelation("corr-" + instance)
                    .withStamps(new VersionStamps("gpt-4o-mini@2026-10-01", null, null, null, null, "flow@3")));
        }

        Response first = caller().queryParam("instanceId", instance).queryParam("limit", 2)
                .when().get("/audit/events");
        first.then().statusCode(200)
                .body("items.size()", equalTo(2))
                .body("items[0].chainId", equalTo(chain))
                .body("items[0].attributes['stamp.model']", equalTo("gpt-4o-mini@2026-10-01"))
                .body("items[0].attributes['oa.instance']", equalTo(instance))
                .body("nextCursor", notNullValue());
        String cursor = first.jsonPath().getString("nextCursor");

        Response second = caller().queryParam("instanceId", instance).queryParam("limit", 2)
                .queryParam("cursor", cursor).when().get("/audit/events");
        second.then().statusCode(200).body("items.size()", equalTo(2));
        String lastCursor = second.jsonPath().getString("nextCursor");

        caller().queryParam("instanceId", instance).queryParam("limit", 2).queryParam("cursor", lastCursor)
                .when().get("/audit/events")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("nextCursor", nullValue());
    }

    @Test
    void devOpenRemovesThePolicyRequirementNotTheAuthenticationRequirement() {
        // No credential at all: 401 on every endpoint, although agentic.dev.audit-open is true.
        given().when().get("/audit/events").then().statusCode(401).contentType("application/problem+json");
        given().when().get("/audit/events/01ARZ3NDEKTSV4RRFFQ69G5FAV").then().statusCode(401);
        given().when().post("/audit/verify").then().statusCode(401);
        // Identity headers that are incomplete do not identify anybody either.
        given().header("X-Subject", "auditor").when().get("/audit/events").then().statusCode(401);
    }

    @Test
    void eventsCanBeFilteredByCorrelationAndType() {
        String instance = "wf-" + UUID.randomUUID();
        String correlation = "corr-" + UUID.randomUUID();
        recorder.record(AuditRequest.of(AGENT, "tool.call.allowed", "tool", "search")
                .inChain("api-" + UUID.randomUUID()).forInstance(instance).withCorrelation(correlation));
        recorder.record(AuditRequest.of(AGENT, "tool.call.denied", "tool", "search")
                .inChain("api-" + UUID.randomUUID()).forInstance(instance).withCorrelation(correlation));

        caller().queryParam("correlationId", correlation).queryParam("type", "tool.call.denied")
                .when().get("/audit/events")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].type", equalTo("tool.call.denied"));
    }

    @Test
    void anEventIsReadByItsId() {
        var stored = recorder.record(AuditRequest.of(AGENT, "decision.allowed", "run", "run-9")
                .inChain("api-" + UUID.randomUUID()));

        caller().when().get("/audit/events/" + stored.id().value())
                .then().statusCode(200)
                .body("eventId", equalTo(stored.id().value()))
                .body("hash", equalTo(stored.hash()))
                .body("actor.kind", equalTo("AGENT"));
    }

    @Test
    void anUnknownEventIsANotFoundProblem() {
        caller().when().get("/audit/events/01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .then().statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404));
    }

    @Test
    void badInputIsABadRequestProblem() {
        caller().queryParam("limit", 0).when().get("/audit/events").then().statusCode(400)
                .contentType("application/problem+json");
        caller().queryParam("cursor", "not-a-cursor!").when().get("/audit/events").then().statusCode(400);
        caller().queryParam("from", "yesterday").when().get("/audit/events").then().statusCode(400);
        caller().queryParam("chainId", "bad chain!").when().get("/audit/events").then().statusCode(400);
        caller().when().get("/audit/events/not-a-ulid").then().statusCode(400);
        caller().queryParam("chainId", "bad chain!").when().post("/audit/verify").then().statusCode(400);
    }

    @Test
    void verifyingOneChainReportsItIntact() {
        String chain = "api-" + UUID.randomUUID();
        recorder.record(AuditRequest.of(AGENT, "step.one", "run", "run-1").inChain(chain));
        recorder.record(AuditRequest.of(AGENT, "step.two", "run", "run-1").inChain(chain));

        caller().queryParam("chainId", chain).when().post("/audit/verify")
                .then().statusCode(200)
                .body("intact", equalTo(true))
                .body("chains[0].chainId", equalTo(chain))
                .body("chains[0].eventCount", equalTo(2));
    }

    @Test
    void verifyingEveryChainListsThem() {
        recorder.record(AuditRequest.of(AGENT, "step.one", "run", "run-1").inChain("api-" + UUID.randomUUID()));

        caller().when().post("/audit/verify")
                .then().statusCode(200)
                .body("chains.size()", greaterThan(0));
    }

    @Test
    void theConfiguredRedactionRuleAppliesToTheStoredEvent() {
        String instance = "wf-" + UUID.randomUUID();
        var stored = recorder.record(AuditRequest.of(AGENT, "customer.updated", "customer", "c-1")
                .inChain("api-" + UUID.randomUUID()).forInstance(instance)
                .withPayload(Map.of("customer.iban", "AE070331234567890123456", "channel", "portal")));

        String redacted = stored.attributes().get("field.customer.iban");
        org.junit.jupiter.api.Assertions.assertTrue(redacted.startsWith("redacted:sha256:"), redacted);
        org.junit.jupiter.api.Assertions.assertEquals("portal", stored.attributes().get("field.channel"));
        org.junit.jupiter.api.Assertions.assertEquals("STORE_REDACTED",
                stored.attributes().get("oa.payload.form"));
        caller().when().get("/audit/events/" + stored.id().value())
                .then().statusCode(200)
                .body("attributes['field.customer.iban']", equalTo(redacted));
    }

    @Test
    void theScheduledVerificationJobRunsWithoutFailing() {
        verificationJob.verifyAllChains();
    }
}
