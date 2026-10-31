package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.count;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.standardBody;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.identity.FakeIdpResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthorityStore;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP-103: the instance API goes through the policy enforcement point. POST /instances and
 * POST /instances/{id}/reauthorize need oa.operate, GET /instances/{id} needs oa.inspect. Every refusal (no scope,
 * wrong scope, unavailable policy, unavailable audit, expired signed identity) is shown to leave no effect: no registry
 * row, no engine call, no authority change, no history row. The positive paths run on the real operations Cedar
 * snapshot. Signed expiry is proved with real RS256 tokens of the fake issuer (docs/identity.md section 12: verified
 * against the fake only).
 */
@QuarkusTest
@TestProfile(InstancePolicyProfile.class)
@QuarkusTestResource(value = FakeIdpResource.class, restrictToAnnotatedClass = true)
class InstanceApiPolicyTest {

    private static final String UNKNOWN = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private final Subject planner = new Subject(SubjectKind.AGENT, "planner");

    @Inject
    CountingWorkflowEngine engine;

    @Inject
    DataSource dataSource;

    @Inject
    DelegationGrants grants;

    @Inject
    InstanceAuthorityStore authorityStore;

    @BeforeEach
    @AfterEach
    void clean() {
        InstancePolicyProfile.reset();
        engine.reset();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static RequestSpecification who(String tenant, String subject, String scopes) {
        RequestSpecification spec = given().header("X-Tenant", tenant).header("X-Subject", subject);
        return scopes == null ? spec : spec.header("X-Scopes", scopes);
    }

    private static ValidatableResponse start(String tenant, String subject, String scopes, Map<String, Object> body) {
        return who(tenant, subject, scopes).contentType("application/json").body(body)
                .when().post("/instances").then();
    }

    private static ValidatableResponse read(String tenant, String subject, String scopes, String id) {
        return who(tenant, subject, scopes).when().get("/instances/" + id).then();
    }

    private static ValidatableResponse renew(String tenant, String subject, String scopes, String id, String grant) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delegationGrantId", grant);
        return who(tenant, subject, scopes).contentType("application/json").body(body)
                .when().post("/instances/" + id + "/reauthorize").then();
    }

    private static String token(String subject, String tenant, String scope, Duration lifetime) throws Exception {
        FakeIdentityProvider idp = FakeIdpResource.idp();
        Map<String, Object> claims = idp.baseClaims(subject, FakeIdpResource.AUDIENCE, lifetime);
        claims.put("scope", scope);
        claims.put("tenant", tenant);
        return idp.signClaims(claims);
    }

    private static RequestSpecification bearer(String token) {
        return given().header("Authorization", "Bearer " + token);
    }

    private String started(String tenant, String grantId) {
        Map<String, Object> body = standardBody(newKey());
        if (grantId != null) {
            body.put("delegationGrantId", grantId);
        }
        return start(tenant, "alice", "oa.operate", body).statusCode(202).extract().path("id");
    }

    private DelegationGrant grantOf(String tenant, String user) {
        Subject subject = new Subject(SubjectKind.USER, user);
        return grants.grant(tenant, subject, subject, planner, Set.of("cases:read"),
                Instant.now().plus(Duration.ofHours(1)), null);
    }

    private long instances(String tenant) {
        return count(dataSource, "select count(*) from task.instance where tenant = ?", tenant);
    }

    private long decisions(String resource, String outcome) {
        return count(dataSource, "select count(*) from audit.event where event_type = 'policy.decision'"
                + " and resource_id = ? and attributes ->> 'policy.outcome' = ?", resource, outcome);
    }

    private long history(String id) {
        return count(dataSource, "select count(*) from task.instance_event where instance_id = ?", id);
    }

    private String authority(String id, String column) throws Exception {
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement("select " + column + " from identity.instance_authority where instance_id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** An instance in REAUTHORIZATION_REQUIRED, started by alice under a grant that was then revoked. */
    private record Waiting(String tenant, String id, DelegationGrant fresh) { }

    private Waiting waiting() {
        String tenant = newTenant();
        DelegationGrant old = grantOf(tenant, "alice");
        String id = started(tenant, old.id().value());
        grants.revoke(old.id(), new Subject(SubjectKind.USER, "alice"), "withdrawn", null);
        authorityStore.markReauthorizationRequired(Ulid.parse(id), "grant.revoked", Instant.now());
        return new Waiting(tenant, id, grantOf(tenant, "alice"));
    }

    // ---- no scope, wrong scope: 403 and no effect ----------------------------------------------------------------------

    @Test
    void aStartWithoutTheOperateScopeIs403AndStartsNothing() {
        String tenant = newTenant();
        long before = decisions("instance:start", "DENY");
        String[] scopeSets = {null, "", "oa.inspect", "oa.recover", "oa.operator", "oa.agent.run,oa.audit.read"};
        for (String scopes : scopeSets) {
            // a malformed body from an unauthorized caller is 403 too: the body is not read before the policy
            start(tenant, "alice", scopes, standardBody(newKey())).statusCode(403)
                    .contentType("application/problem+json").body("status", equalTo(403));
            start(tenant, "alice", scopes, Map.of()).statusCode(403);
        }
        assertEquals(0, instances(tenant));
        assertEquals(0, engine.prepares());
        assertEquals(0, engine.starts());
        assertEquals(0, count(dataSource, "select count(*) from identity.instance_authority a"
                + " join task.instance i on i.id = a.instance_id where i.tenant = ?", tenant));
        assertEquals(scopeSets.length * 2L, decisions("instance:start", "DENY") - before, "each refusal is audited");
    }

    @Test
    void aReadWithoutTheInspectScopeIs403EvenForTheOwnerAndAMalformedIdIsNotA404() {
        String tenant = newTenant();
        String id = started(tenant, null);
        long before = decisions("instance:get", "DENY");
        for (String scopes : new String[] {null, "", "oa.operate", "oa.recover", "oa.inspector"}) {
            read(tenant, "alice", scopes, id).statusCode(403).contentType("application/problem+json");
            read(tenant, "alice", scopes, "not-a-ulid").statusCode(403);
            read(tenant, "alice", scopes, UNKNOWN).statusCode(403);
        }
        assertEquals(15, decisions("instance:get", "DENY") - before);
    }

    @Test
    void aReauthorizationWithoutTheOperateScopeIs403AndChangesNothing() throws Exception {
        Waiting w = waiting();
        String grantBefore = authority(w.id(), "grant_id");
        long historyBefore = history(w.id());
        long before = decisions("instance:reauthorize", "DENY");
        for (String scopes : new String[] {null, "", "oa.inspect", "oa.recover"}) {
            renew(w.tenant(), "alice", scopes, w.id(), w.fresh().id().value()).statusCode(403);
        }
        assertEquals("REAUTHORIZATION_REQUIRED", authority(w.id(), "state"));
        assertEquals(grantBefore, authority(w.id(), "grant_id"));
        assertEquals(historyBefore, history(w.id()), "no recovery ran: the instance history did not move");
        assertEquals(4, decisions("instance:reauthorize", "DENY") - before);
    }

    @Test
    void theScopesDoNotCrossRoutes() {
        String tenant = newTenant();
        String id = started(tenant, null);
        start(tenant, "alice", "oa.inspect", standardBody(newKey())).statusCode(403);
        read(tenant, "alice", "oa.operate", id).statusCode(403);
        read(tenant, "alice", "oa.inspect", id).statusCode(200);
        assertEquals(1, instances(tenant));
    }

    @Test
    void anAnonymousCallerIs401OnEveryRouteBeforeAnyDecision() {
        long decisionsBefore = count(dataSource, "select count(*) from audit.event where event_type = 'policy.decision'"
                + " and resource_id like 'instance:%'");
        given().contentType("application/json").body(standardBody(newKey())).when().post("/instances")
                .then().statusCode(401);
        given().when().get("/instances/" + UNKNOWN).then().statusCode(401);
        given().contentType("application/json").body("{}").when().post("/instances/" + UNKNOWN + "/reauthorize")
                .then().statusCode(401);
        assertEquals(decisionsBefore, count(dataSource, "select count(*) from audit.event where event_type ="
                + " 'policy.decision' and resource_id like 'instance:%'"));
    }

    // ---- real positive paths -------------------------------------------------------------------------------------------

    @Test
    void scopedCallersStartReadAndRepeatWithTheDocumentedStatuses() {
        String tenant = newTenant();
        Map<String, Object> body = standardBody(newKey());
        long allowed = decisions("instance:start", "ALLOW");
        String id = start(tenant, "alice", "oa.operate", body).statusCode(202).extract().path("id");
        start(tenant, "alice", "oa.operate", body).statusCode(200).body("id", equalTo(id));   // idempotent repeat
        assertEquals(1, engine.starts());
        assertEquals(1, instances(tenant));
        assertEquals(2, decisions("instance:start", "ALLOW") - allowed, "both permits are audited");

        // the read stays tenant-wide: another user of the tenant with oa.inspect sees it; another tenant does not
        read(tenant, "alice", "oa.inspect", id).statusCode(200).body("id", equalTo(id));
        read(tenant, "bob", "oa.inspect", id).statusCode(200).body("id", equalTo(id));
        read(newTenant(), "bob", "oa.inspect", id).statusCode(404);
        read(tenant, "bob", "oa.inspect", UNKNOWN).statusCode(404);
        read(tenant, "bob", "oa.inspect", "not-a-ulid").statusCode(404);
        // a mistake in the request is still 400 for a permitted caller
        start(tenant, "alice", "oa.operate", Map.of("definitionId", "no-namespace-separator")).statusCode(400);
    }

    @Test
    void aPermittedReauthorizationStillNeedsTheExactStarterAndTenant() throws Exception {
        Waiting w = waiting();
        String fresh = w.fresh().id().value();
        renew(w.tenant(), "bob", "oa.operate", w.id(), fresh).statusCode(403);              // not the starter
        renew(newTenant(), "alice", "oa.operate", w.id(), fresh).statusCode(404);           // another tenant
        assertEquals("REAUTHORIZATION_REQUIRED", authority(w.id(), "state"));

        renew(w.tenant(), "alice", "oa.operate", w.id(), fresh).statusCode(200).body("id", equalTo(w.id()));
        assertEquals("AUTHORIZED", authority(w.id(), "state"));
        assertEquals(fresh, authority(w.id(), "grant_id"));
    }

    // ---- unavailable policy or audit: 503, no effect, no emergency fallback --------------------------------------------

    @Test
    void anUnavailablePolicyOrAuditIs503OnEveryRouteAndNothingHappens() throws Exception {
        Waiting w = waiting();
        String owned = started(w.tenant(), null);
        String tenant = newTenant();
        long historyBefore = history(w.id());
        engine.reset();   // the two setup starts above are not part of what is counted
        for (Runnable failure : new Runnable[] {() -> InstancePolicyProfile.policyFails = true,
                () -> InstancePolicyProfile.auditFails = true}) {
            failure.run();
            // oa.recover is the one scope the safe-recovery rule accepts for cancel and pause: not for these routes
            for (String scopes : new String[] {"oa.operate,oa.inspect", "oa.operate,oa.inspect,oa.recover"}) {
                start(tenant, "alice", scopes, standardBody(newKey())).statusCode(503);
                read(w.tenant(), "alice", scopes, owned).statusCode(503);
                renew(w.tenant(), "alice", scopes, w.id(), w.fresh().id().value()).statusCode(503);
            }
            InstancePolicyProfile.reset();
        }
        assertEquals(0, instances(tenant));
        assertEquals(0, engine.prepares());
        assertEquals("REAUTHORIZATION_REQUIRED", authority(w.id(), "state"));
        assertEquals(historyBefore, history(w.id()));
    }

    // ---- signed identity ends while policy or audit blocks: 401, no effect ----------------------------------------------

    @Test
    void anIdentityThatExpiresDuringPolicyAndAuditStartsAndChangesNothing() throws Exception {
        Waiting w = waiting();
        String tenant = newTenant();
        String owned = started(w.tenant(), null);
        long historyBefore = history(w.id());
        engine.reset();
        // a long token works; then a 2 s token expires while the decision audit blocks for 3 s
        bearer(token("alice", w.tenant(), "oa.inspect", Duration.ofMinutes(5))).when().get("/instances/" + owned)
                .then().statusCode(200);
        InstancePolicyProfile.auditDelayMillis = 3_000;

        bearer(token("alice", tenant, "oa.operate", Duration.ofSeconds(2))).contentType("application/json")
                .body(standardBody(newKey())).when().post("/instances").then().statusCode(401);
        bearer(token("alice", w.tenant(), "oa.inspect", Duration.ofSeconds(2))).when().get("/instances/" + owned)
                .then().statusCode(401);
        bearer(token("alice", w.tenant(), "oa.operate", Duration.ofSeconds(2))).contentType("application/json")
                .body(Map.of("delegationGrantId", w.fresh().id().value()))
                .when().post("/instances/" + w.id() + "/reauthorize").then().statusCode(401);

        assertEquals(0, instances(tenant));
        assertEquals(0, engine.prepares());
        assertEquals("REAUTHORIZATION_REQUIRED", authority(w.id(), "state"));
        assertEquals(historyBefore, history(w.id()));
    }

    // ---- signed identity ends after the work, before the output: 401 and no view ---------------------------------------

    @Test
    void anIdentityThatExpiresDuringTheStartGetsNoViewAndTheSameKeyFindsTheInstance() throws Exception {
        String tenant = newTenant();
        Map<String, Object> body = standardBody(newKey());
        InstancePolicyProfile.startBlockMillis = 3_000;
        bearer(token("alice", tenant, "oa.operate", Duration.ofSeconds(2))).contentType("application/json").body(body)
                .when().post("/instances").then().statusCode(401);
        // the work was done and is not undone; only the output was withheld
        assertEquals(1, instances(tenant));
        assertEquals(1, engine.starts());
        InstancePolicyProfile.reset();
        bearer(token("alice", tenant, "oa.operate", Duration.ofMinutes(5))).contentType("application/json").body(body)
                .when().post("/instances").then().statusCode(200);
        assertEquals(1, engine.starts(), "the repeat starts nothing");
    }

    @Test
    void anIdentityThatExpiresDuringTheReadGetsNeitherTheViewNorA404() throws Exception {
        String tenant = newTenant();
        String id = started(tenant, null);
        InstancePolicyProfile.readBlockMillis = 3_000;
        bearer(token("alice", tenant, "oa.inspect", Duration.ofSeconds(2))).when().get("/instances/" + id)
                .then().statusCode(401);
        // an unknown id gives no existence signal after the expiry either
        bearer(token("alice", tenant, "oa.inspect", Duration.ofSeconds(2))).when().get("/instances/" + UNKNOWN)
                .then().statusCode(401);
        InstancePolicyProfile.reset();
        bearer(token("alice", tenant, "oa.inspect", Duration.ofMinutes(5))).when().get("/instances/" + id)
                .then().statusCode(200).body("id", equalTo(id));
    }

    @Test
    void anIdentityThatExpiresDuringTheReauthorizationGetsNoViewAndTheRenewalIsNotUndone() throws Exception {
        Waiting w = waiting();
        InstancePolicyProfile.readBlockMillis = 3_000;
        bearer(token("alice", w.tenant(), "oa.operate", Duration.ofSeconds(2))).contentType("application/json")
                .body(Map.of("delegationGrantId", w.fresh().id().value()))
                .when().post("/instances/" + w.id() + "/reauthorize").then().statusCode(401);
        assertEquals("AUTHORIZED", authority(w.id(), "state"));
        assertEquals(w.fresh().id().value(), authority(w.id(), "grant_id"));
    }

    @Test
    void aStillValidTokenGetsEveryOutput() throws Exception {
        String tenant = newTenant();
        String operate = token("alice", tenant, "oa.operate", Duration.ofMinutes(5));
        String id = bearer(operate).contentType("application/json").body(standardBody(newKey()))
                .when().post("/instances").then().statusCode(202).extract().path("id");
        bearer(token("alice", tenant, "oa.inspect", Duration.ofMinutes(5))).when().get("/instances/" + id)
                .then().statusCode(200).body("id", equalTo(id));
    }
}
