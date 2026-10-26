package io.github.bassemzohdy.openworkflow.agentic.identity;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthorityStore;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The instance API with delegation grants (WP-102 slice d): an instance can be started under the caller's own grant,
 * the identity is stored as identifiers, inspect shows it, and only the user who started the instance can renew it.
 * Callers are verified tokens of the fake issuer (docs/identity.md section 12: verified against the fake only).
 */
@QuarkusTest
@QuarkusTestResource(value = FakeIdpResource.class, restrictToAnnotatedClass = true)
class InstanceAuthorityApiTest {

    @Inject
    DelegationGrants grants;

    @Inject
    InstanceAuthorityStore authorityStore;

    @Inject
    DataSource dataSource;

    private final Subject planner = new Subject(SubjectKind.AGENT, "planner");

    private static String token(String subject, String tenant, String scope) throws Exception {
        FakeIdentityProvider idp = FakeIdpResource.idp();
        Map<String, Object> claims = idp.baseClaims(subject, FakeIdpResource.AUDIENCE, Duration.ofMinutes(5));
        claims.put("scope", scope);
        claims.put("tenant", tenant);
        return idp.signClaims(claims);
    }

    private static String newTenant() {
        return "auth-" + UUID.randomUUID();
    }

    private DelegationGrant grantOf(String tenant, String user, Duration lifetime) {
        Subject subject = new Subject(SubjectKind.USER, user);
        return grants.grant(tenant, subject, subject, planner, Set.of("cases:read"), Instant.now().plus(lifetime), null);
    }

    private static Map<String, Object> body(String grantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionId", "agentic/lifecycle-probe");
        body.put("definitionVersion", "0.1.0");
        body.put("input", Map.of("effectUrl", "http://localhost:" + io.restassured.RestAssured.port
                + "/dev/lifecycle/effects", "mode", "hold"));
        body.put("idempotencyKey", "key-" + UUID.randomUUID());
        if (grantId != null) {
            body.put("delegationGrantId", grantId);
        }
        return body;
    }

    private static ValidatableResponse start(String bearer, String grantId) {
        return given().header("Authorization", "Bearer " + bearer).header("X-Correlation-Id", "corr-api-1")
                .contentType("application/json").body(body(grantId)).when().post("/instances").then();
    }

    private static ValidatableResponse reauthorize(String bearer, String instanceId, String grantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (grantId != null) {
            body.put("delegationGrantId", grantId);
        }
        return given().header("Authorization", "Bearer " + bearer).contentType("application/json").body(body)
                .when().post("/instances/" + instanceId + "/reauthorize").then();
    }

    private String scalar(String sql, String param) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void anInstanceStartedUnderTheCallersGrantStoresSubjectGrantAndCorrelationAndInspectShowsThem() throws Exception {
        String tenant = newTenant();
        DelegationGrant grant = grantOf(tenant, "alice", Duration.ofHours(1));

        String id = start(token("alice", tenant, "oa.operate"), grant.id().value()).statusCode(202).extract().path("id");

        assertEquals("alice", scalar("select subject_id from identity.instance_authority where instance_id = ?", id));
        assertEquals(grant.id().value(), scalar("select grant_id from identity.instance_authority where instance_id = ?", id));
        assertEquals("corr-api-1", scalar("select correlation_id from identity.instance_authority where instance_id = ?", id));
        given().header("Authorization", "Bearer " + token("ops", tenant, "oa.inspect"))
                .when().get("/operations/instances/" + id).then().statusCode(200)
                .body("authority.subject", equalTo("alice"))
                .body("authority.grantId", equalTo(grant.id().value()))
                .body("authority.correlationId", equalTo("corr-api-1"))
                .body("authority.state", equalTo("AUTHORIZED"))
                .body("authority.reason", nullValue());
    }

    @Test
    void anInstanceWithoutAGrantHasAnAuthorityRowWithoutOne() throws Exception {
        String tenant = newTenant();

        String id = start(token("alice", tenant, "oa.operate"), null).statusCode(202).extract().path("id");

        assertEquals("alice", scalar("select subject_id from identity.instance_authority where instance_id = ?", id));
        assertEquals(null, scalar("select grant_id from identity.instance_authority where instance_id = ?", id));
    }

    @Test
    void aGrantThatIsNotUsableIsRefusedAndNoInstanceIsCreated() throws Exception {
        String tenant = newTenant();
        DelegationGrant others = grantOf(tenant, "bob", Duration.ofHours(1));
        DelegationGrant revoked = grantOf(tenant, "alice", Duration.ofHours(1));
        grants.revoke(revoked.id(), new Subject(SubjectKind.USER, "alice"), "no", null);
        String alice = token("alice", tenant, "oa.operate");

        start(alice, others.id().value()).statusCode(404);
        start(alice, revoked.id().value()).statusCode(409);
        start(alice, "not-a-grant").statusCode(400);
        start(alice, grantOf(newTenant(), "alice", Duration.ofHours(1)).id().value()).statusCode(404);

        assertEquals("0", scalar("select count(*)::text from task.instance where tenant = ?", tenant));
    }

    @Test
    void onlyTheUserWhoStartedTheInstanceRenewsItWithAGrantThatIsTheirOwn() throws Exception {
        String tenant = newTenant();
        DelegationGrant old = grantOf(tenant, "alice", Duration.ofHours(1));
        String alice = token("alice", tenant, "oa.operate");
        String id = start(alice, old.id().value()).statusCode(202).extract().path("id");
        DelegationGrant fresh = grantOf(tenant, "alice", Duration.ofHours(2));

        reauthorize(alice, id, fresh.id().value()).statusCode(409);        // it does not wait for re-authorization
        grants.revoke(old.id(), new Subject(SubjectKind.USER, "alice"), "withdrawn", null);
        assertTrue(authorityStore.markReauthorizationRequired(Ulid.parse(id), "grant.revoked", Instant.now()));
        reauthorize(token("bob", tenant, "oa.operate"), id, fresh.id().value()).statusCode(403);
        reauthorize(token("alice", newTenant(), "oa.operate"), id, fresh.id().value()).statusCode(404);
        reauthorize(alice, id, null).statusCode(400);
        reauthorize(alice, "01ARZ3NDEKTSV4RRFFQ69G5FAV", fresh.id().value()).statusCode(404);
        reauthorize(alice, id, old.id().value()).statusCode(409);          // the revoked grant is not usable

        reauthorize(alice, id, fresh.id().value()).statusCode(200).body("id", equalTo(id));

        assertEquals(fresh.id().value(), scalar("select grant_id from identity.instance_authority where instance_id = ?", id));
        assertEquals("AUTHORIZED", scalar("select state from identity.instance_authority where instance_id = ?", id));
    }

    @Test
    void noTokenIs401OnTheNewEndpoint() {
        given().contentType("application/json").body("{}").when()
                .post("/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV/reauthorize").then().statusCode(401);
    }
}
