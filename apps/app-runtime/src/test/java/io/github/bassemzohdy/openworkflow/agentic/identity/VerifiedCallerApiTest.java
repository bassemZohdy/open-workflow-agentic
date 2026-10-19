package io.github.bassemzohdy.openworkflow.agentic.identity;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The operations API and the instance API with a verified caller (WP-102, deliverable a). The application verifies
 * real RS256 tokens against the testkit's fake issuer over HTTP. The development header seam is off (the default), so
 * the only way to be a caller is a valid token, and the scopes the policy sees are the token's.
 *
 * <p>Verified against the fake issuer only; nothing here proves Keycloak's tokens (docs/identity.md section 8).
 */
@QuarkusTest
@QuarkusTestResource(value = FakeIdpResource.class, restrictToAnnotatedClass = true)
class VerifiedCallerApiTest {

    private static final String AUDIENCE = FakeIdpResource.AUDIENCE;

    private static String token(String subject, String tenant, String scope, Duration lifetime, Object audience,
                                Consumer<Map<String, Object>> tweak) throws Exception {
        FakeIdentityProvider idp = FakeIdpResource.idp();
        Map<String, Object> claims = idp.baseClaims(subject, null, lifetime);
        claims.put("aud", audience);
        if (scope != null) {
            claims.put("scope", scope);
        }
        if (tenant != null) {
            claims.put("tenant", tenant);
        }
        tweak.accept(claims);
        return idp.signClaims(claims);
    }

    private static String token(String subject, String tenant, String scope) throws Exception {
        return token(subject, tenant, scope, Duration.ofMinutes(5), AUDIENCE, claims -> { });
    }

    private static String newTenant() {
        return "idp-" + UUID.randomUUID();
    }

    private static Map<String, Object> startBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionId", "agentic/lifecycle-probe");
        body.put("definitionVersion", "0.1.0");
        body.put("input", Map.of("effectUrl", "http://localhost:" + io.restassured.RestAssured.port
                + "/dev/lifecycle/effects", "mode", "hold"));
        body.put("idempotencyKey", "key-" + UUID.randomUUID());
        return body;
    }

    private static ValidatableResponse start(String bearer) {
        return given().header("Authorization", "Bearer " + bearer).contentType("application/json").body(startBody())
                .when().post("/instances").then();
    }

    // ---- refusals ------------------------------------------------------------------------------------------------------

    @Test
    void noTokenIs401WithAChallengeAndAProblemBody() {
        given().when().get("/operations/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV").then()
                .statusCode(401)
                .header("WWW-Authenticate", equalTo("Bearer"))
                .contentType("application/problem+json")
                .body("status", equalTo(401))
                .body("correlationId", notNullValue());
    }

    @Test
    void anExpiredTokenIs401() throws Exception {
        String expired = token("alice", newTenant(), "oa.inspect", Duration.ofMinutes(-10), AUDIENCE, c -> { });
        given().header("Authorization", "Bearer " + expired)
                .when().get("/operations/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV").then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"))
                .contentType("application/problem+json")
                .body("detail", equalTo("the bearer token has expired"));
    }

    @Test
    void aWrongAudienceAForgedSignatureAndAMissingTenantAre401() throws Exception {
        String wrongAudience = token("alice", newTenant(), "oa.inspect", Duration.ofMinutes(5), "tool-search",
                c -> { });
        String missingTenant = token("alice", null, "oa.inspect");
        String delegated = token("alice", newTenant(), "oa.inspect", Duration.ofMinutes(5), AUDIENCE,
                c -> c.put("act", Map.of("sub", "agent-7")));
        String forged;
        try (FakeIdentityProvider other = new FakeIdentityProvider(Clock.systemUTC())) {
            Map<String, Object> claims = other.baseClaims("alice", AUDIENCE, Duration.ofMinutes(5));
            claims.put("iss", FakeIdpResource.idp().issuer());
            claims.put("tenant", newTenant());
            claims.put("scope", "oa.inspect oa.operate oa.recover");
            forged = other.signClaims(claims);
        }
        for (String bad : List.of(wrongAudience, missingTenant, delegated, forged, "not.a.jwt")) {
            given().header("Authorization", "Bearer " + bad)
                    .when().get("/operations/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV").then()
                    .statusCode(401).contentType("application/problem+json")
                    .body("detail", equalTo("the bearer token is not valid"));
        }
    }

    @Test
    void headersCannotAddScopesOrChangeTheTenantOfAVerifiedCaller() throws Exception {
        String tenant = newTenant();
        String noScopes = token("alice", tenant, "profile");
        // X-Scopes claims every operator scope. It must be ignored: the policy sees the token's scopes only.
        given().header("Authorization", "Bearer " + noScopes)
                .header("X-Scopes", "oa.inspect,oa.operate,oa.recover").header("X-Tenant", tenant)
                .header("X-Subject", "alice")
                .when().get("/operations/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV").then()
                .statusCode(403).contentType("application/problem+json");
        given().header("Authorization", "Bearer " + noScopes)
                .header("X-Scopes", "oa.operate,oa.recover").header("Idempotency-Key", "k-" + UUID.randomUUID())
                .when().post("/operations/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV/cancel").then()
                .statusCode(403);
    }

    // ---- the verified caller drives the instance and operations APIs ----------------------------------------------------

    @Test
    void theTokenScopesDecideWhatTheOperationsApiAllows() throws Exception {
        String tenant = newTenant();
        String owner = token("alice", tenant, "profile");
        String id = start(owner).statusCode(202).extract().path("id");

        // The instance belongs to the token's tenant, whatever X-Tenant says.
        given().header("Authorization", "Bearer " + owner).header("X-Tenant", "someone-else")
                .when().get("/instances/" + id).then().statusCode(200).body("id", equalTo(id));
        given().header("Authorization", "Bearer " + token("alice", newTenant(), "profile"))
                .when().get("/instances/" + id).then().statusCode(404);

        String inspector = token("ops-1", tenant, "oa.inspect");
        given().header("Authorization", "Bearer " + inspector)
                .when().get("/operations/instances/" + id).then().statusCode(200).body("instance.id", equalTo(id));
        given().header("Authorization", "Bearer " + inspector).header("Idempotency-Key", "c-" + UUID.randomUUID())
                .when().post("/operations/instances/" + id + "/cancel").then().statusCode(403);

        String operator = token("ops-2", tenant, "oa.operate oa.inspect");
        given().header("Authorization", "Bearer " + operator).header("Idempotency-Key", "c-" + UUID.randomUUID())
                .when().post("/operations/instances/" + id + "/cancel").then().statusCode(202);
    }
}
