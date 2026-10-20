package io.github.bassemzohdy.openworkflow.agentic.audit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.identity.FakeIdpResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAccess;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The audit API with a verified caller (WP-102, "bind verified caller identity to audit access"). The application
 * verifies real RS256 bearer tokens against the testkit's fake issuer over HTTP, and a recording policy is bound, so
 * the test can see exactly what the policy is asked. The development header seam is off (the default).
 *
 * <p>Verified against the fake issuer only; Keycloak's tokens are the subject of the real-provider run recorded in
 * docs/identity.md section 9.
 */
@QuarkusTest
@TestProfile(AuditVerifiedCallerApiTest.WithRecordingPolicy.class)
@QuarkusTestResource(value = FakeIdpResource.class, restrictToAnnotatedClass = true)
class AuditVerifiedCallerApiTest {

    /** Binds {@link RecordingPdp}. Nothing else is configured: the header seam and the dev-open flag stay off. */
    public static class WithRecordingPolicy implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(RecordingPdp.class);
        }
    }

    private static final String AUDIENCE = FakeIdpResource.AUDIENCE;
    private static final String SOME_EVENT = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @BeforeEach
    void freshPolicy() {
        RecordingPdp.reset();
    }

    private static String token(String subject, String scope, Duration lifetime,
                                Consumer<Map<String, Object>> tweak) throws Exception {
        FakeIdentityProvider idp = FakeIdpResource.idp();
        Map<String, Object> claims = idp.baseClaims(subject, null, lifetime);
        claims.put("aud", AUDIENCE);
        claims.put("tenant", "audit-tenant");
        if (scope != null) {
            claims.put("scope", scope);
        }
        tweak.accept(claims);
        return idp.signClaims(claims);
    }

    private static String token(String subject, String scope, Consumer<Map<String, Object>> tweak) throws Exception {
        return token(subject, scope, Duration.ofMinutes(5), tweak);
    }

    private static String token(String subject, String scope) throws Exception {
        return token(subject, scope, claims -> { });
    }

    private static AuthorizationRequest onlyRequest() {
        List<AuthorizationRequest> seen = RecordingPdp.seen();
        assertEquals(1, seen.size(), "the policy must be asked exactly once; it was asked " + seen);
        return seen.get(0);
    }

    // ---- the policy decides about the verified caller --------------------------------------------------------------------

    @Test
    void thePolicyIsAskedAboutTheTokensSubjectAndScopesAndAnAllowedCallerReadsEvents() throws Exception {
        RecordingPdp.reset("alice");
        given().header("Authorization", "Bearer " + token("alice", "audit.read profile"))
                .when().get("/audit/events")
                .then().statusCode(200);

        AuthorizationRequest asked = onlyRequest();
        assertEquals(SubjectKind.USER, asked.subject().kind());
        assertEquals("alice", asked.subject().id());
        assertEquals(AuditAccess.READ, asked.resource());
        assertEquals(ActionKind.DATA_ACCESS, asked.action());
        assertEquals(Set.of("audit.read", "profile"), asked.context().scopes());
    }

    @Test
    void verifyingAskedAboutTheVerifyActionForTheSameCaller() throws Exception {
        RecordingPdp.reset("alice");
        given().header("Authorization", "Bearer " + token("alice", "audit.read"))
                .when().post("/audit/verify")
                .then().statusCode(200);

        AuthorizationRequest asked = onlyRequest();
        assertEquals(AuditAccess.VERIFY, asked.resource());
        assertEquals("alice", asked.subject().id());
    }

    @Test
    void aVerifiedCallerThePolicyDeniesGets403AndNothingIsRead() throws Exception {
        RecordingPdp.reset("alice");
        given().header("Authorization", "Bearer " + token("mallory", "audit.read"))
                .when().get("/audit/events/" + SOME_EVENT)
                .then().statusCode(403)
                .contentType("application/problem+json")
                .body("status", equalTo(403));
        assertEquals("mallory", onlyRequest().subject().id());
    }

    @Test
    void anAgentTokenReachesThePolicyAsAnAgent() throws Exception {
        RecordingPdp.reset();
        given().header("Authorization", "Bearer " + token("agent-7", "audit.read", c -> c.put("sub_kind", "AGENT")))
                .when().get("/audit/events")
                .then().statusCode(403);

        AuthorizationRequest asked = onlyRequest();
        assertEquals(SubjectKind.AGENT, asked.subject().kind());
        assertEquals("agent-7", asked.subject().id());
    }

    @Test
    void requestHeadersCannotChangeWhoThePolicySeesOrWhatScopesItGets() throws Exception {
        RecordingPdp.reset("alice");
        given().header("Authorization", "Bearer " + token("alice", "profile"))
                .header("X-Subject", "admin").header("X-Tenant", "other").header("X-Scopes", "audit.admin,audit.read")
                .when().get("/audit/events")
                .then().statusCode(200);

        AuthorizationRequest asked = onlyRequest();
        assertEquals("alice", asked.subject().id());
        assertEquals(Set.of("profile"), asked.context().scopes());
    }

    // ---- no usable credential: 401, and the policy is never consulted ---------------------------------------------------

    @Test
    void noTokenIs401WithAChallengeOnEveryEndpointAndThePolicyIsNotAsked() {
        RecordingPdp.reset("anonymous", "alice");
        given().when().get("/audit/events").then().statusCode(401)
                .header("WWW-Authenticate", equalTo("Bearer")).contentType("application/problem+json")
                .body("status", equalTo(401));
        given().when().get("/audit/events/" + SOME_EVENT).then().statusCode(401)
                .contentType("application/problem+json");
        given().when().post("/audit/verify").then().statusCode(401).contentType("application/problem+json");
        assertTrue(RecordingPdp.seen().isEmpty(), "an unauthenticated request must never reach the policy");
    }

    @Test
    void anExpiredAForgedAWrongAudienceAndADelegatedTokenAre401AndThePolicyIsNotAsked() throws Exception {
        RecordingPdp.reset("alice");
        String expired = token("alice", "audit.read", Duration.ofMinutes(-10), c -> { });
        String wrongAudience = token("alice", "audit.read", c -> c.put("aud", "tool-search"));
        String delegated = token("alice", "audit.read", c -> c.put("act", Map.of("sub", "agent-7")));
        String forged;
        try (FakeIdentityProvider other = new FakeIdentityProvider(Clock.systemUTC())) {
            Map<String, Object> claims = other.baseClaims("alice", AUDIENCE, Duration.ofMinutes(5));
            claims.put("iss", FakeIdpResource.idp().issuer());
            claims.put("tenant", "audit-tenant");
            claims.put("scope", "audit.read");
            forged = other.signClaims(claims);
        }
        for (String bad : List.of(expired, wrongAudience, delegated, forged, "not.a.jwt")) {
            given().header("Authorization", "Bearer " + bad)
                    .when().get("/audit/events")
                    .then().statusCode(401)
                    .header("WWW-Authenticate", containsString("invalid_token"))
                    .contentType("application/problem+json");
        }
        assertTrue(RecordingPdp.seen().isEmpty(), "a refused credential must never reach the policy");
    }
}
