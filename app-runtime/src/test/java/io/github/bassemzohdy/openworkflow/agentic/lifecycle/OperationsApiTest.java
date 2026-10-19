package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.INSPECT;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.OPERATE;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.auditCount;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.auditEvents;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.historyActor;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.historyWithReason;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.inspect;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.outboxWithReason;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.startRunning;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.status;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The operations API on Postgres, with the trusted development headers and the bundled policy (WP-105d). Each test
 * uses its own tenant and its own instances, so tests do not see each other's rows.
 */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class OperationsApiTest {

    private static final String UNKNOWN_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @Inject
    CountingWorkflowEngine engine;

    @Inject
    JdbcInstanceStore store;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void resetEngine() {
        engine.reset();
    }

    @Test
    void inspectShowsTheRowHistoryOutboxAndRepairsWhenTheScopeIsHeld() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        inspect(tenant, INSPECT, id).statusCode(200)
                .body("instance.id", equalTo(id))
                .body("instance.status", equalTo("RUNNING"))
                .body("history[0].to", equalTo("STARTING"))
                .body("history.size()", equalTo(3))
                .body("outbox.unpublished", notNullValue())
                .body("repairs", notNullValue());

        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "allowed"));
        assertEquals(1, auditCount(dataSource, "operation.result", id, "inspected"));
        assertEquals(0, historyWithReason(dataSource, id, "operator.cancel"), "inspect writes nothing");
    }

    @Test
    void inspectWithoutTheScopeIsForbiddenAndNothingIsReturned() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        inspect(tenant, OPERATE, id).statusCode(403)
                .contentType("application/problem+json")
                .body("status", equalTo(403));

        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "denied"));
        assertEquals(0, auditCount(dataSource, "operation.result", id, "inspected"));
        assertTrue(LifecycleSupport.count(dataSource,
                "select count(*) from audit.event where event_type = 'policy.decision' and resource_id = 'operation:inspect'"
                        + " and attributes ->> 'oa.outcome' = 'DENY' and attributes ->> 'oa.recovery-path' = 'false'") >= 1,
                "the refusal is also a recorded policy decision");
        assertEquals("RUNNING", status(dataSource, id));
    }

    @Test
    void cancelIsRecordedWithTheOperatorAsActorAndTheOutboxRowCommitsWithIt() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);
        String other = startRunning(tenant);

        post(tenant, OPERATE, "cancel-1", id, "cancel").statusCode(202)
                .body("outcome", equalTo("cancel-requested"))
                .body("instance.id", equalTo(id))
                .body("instance.status", anyOf(equalTo("CANCEL_REQUESTED"), equalTo("CANCELLED")));

        assertEquals(1, historyWithReason(dataSource, id, "operator.cancel"));
        assertEquals(OperationsSupport.OPERATOR, historyActor(dataSource, id, "operator.cancel"));
        assertEquals(1, outboxWithReason(dataSource, id, "operator.cancel"));
        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "allowed"));
        assertEquals(1, auditCount(dataSource, "operation.result", id, "cancel-requested"));
        assertEquals(0, historyWithReason(dataSource, other, "operator.cancel"),
                "an operation never writes another instance's rows");
    }

    @Test
    void cancelOfATerminalInstanceIsANoOpWithAnExplicitResult() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);
        completeWithoutEngine(tenant, id);

        post(tenant, OPERATE, "cancel-done", id, "cancel").statusCode(200)
                .body("outcome", equalTo("already-terminal"))
                .body("instance.status", equalTo("COMPLETED"));
        assertEquals(0, historyWithReason(dataSource, id, "operator.cancel"));
        assertEquals("COMPLETED", status(dataSource, id));
    }

    @Test
    void aRepeatedRequestReturnsTheStoredResponseAndChangesNothingElse() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        Map<?, ?> first = post(tenant, OPERATE, "repeat-1", id, "cancel").statusCode(202).extract().as(Map.class);
        Map<?, ?> second = post(tenant, OPERATE, "repeat-1", id, "cancel").statusCode(202).extract().as(Map.class);

        assertEquals(first, second, "a repeat returns the original response");
        assertEquals(1, historyWithReason(dataSource, id, "operator.cancel"), "a repeat changes nothing");
        assertEquals(1, auditCount(dataSource, "operation.result", id, "replayed"));
    }

    @Test
    void theSameKeyForAnotherRequestIsAConflictAndChangesNothing() {
        String tenant = LifecycleSupport.newTenant();
        String first = startRunning(tenant);
        String second = startRunning(tenant);

        post(tenant, OPERATE, "shared-key", first, "cancel").statusCode(202);
        post(tenant, OPERATE, "shared-key", second, "cancel").statusCode(409)
                .body("detail", containsString("different request"));

        assertEquals(0, historyWithReason(dataSource, second, "operator.cancel"));
        assertEquals("RUNNING", status(dataSource, second));
    }

    @Test
    void aMutatingCallWithoutAValidKeyIsBadRequestAndAuditsNothing() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OPERATE, null, id, "cancel").statusCode(400)
                .body("detail", containsString("Idempotency-Key"));
        post(tenant, OPERATE, "bad key!", id, "cancel").statusCode(400);

        assertEquals(0, auditEvents(dataSource, id));
        assertEquals("RUNNING", status(dataSource, id));
    }

    @Test
    void pauseIsAuthorizedAndAnsweredNotImplementedWithoutChangingTheRow() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OPERATE, "pause-1", id, "pause").statusCode(501)
                .contentType("application/problem+json");

        assertEquals("RUNNING", status(dataSource, id));
        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "allowed"));
        assertEquals(1, auditCount(dataSource, "operation.result", id, "not-implemented"));
    }

    @Test
    void retryAppliesOnlyToAStartingRow() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OPERATE, "retry-running", id, "retry").statusCode(409)
                .body("detail", containsString("STARTING"));
        assertEquals("RUNNING", status(dataSource, id));

        completeWithoutEngine(tenant, id);
        post(tenant, OPERATE, "retry-done", id, "retry").statusCode(409);
        assertEquals("COMPLETED", status(dataSource, id));
        assertEquals(0, historyWithReason(dataSource, id, "operator.retry"));
    }

    @Test
    void retryOfAStartingRowStartsItWithoutAddingAnAttempt() {
        String tenant = LifecycleSupport.newTenant();
        engine.failNextStarts(1);
        String id = LifecycleSupport.post(tenant, LifecycleSupport.standardBody(LifecycleSupport.newKey()))
                .statusCode(202)
                .body("status", equalTo("STARTING"))
                .body("startAttempts", equalTo(1))
                .extract().path("id");

        post(tenant, OPERATE, "retry-1", id, "retry").statusCode(202)
                .body("outcome", equalTo("retry-requested"))
                .body("instance.status", equalTo("RUNNING"))
                .body("instance.startAttempts", equalTo(1));

        assertEquals(1, historyWithReason(dataSource, id, "operator.retry"));
        assertEquals(OperationsSupport.OPERATOR, historyActor(dataSource, id, "operator.retry"));
        assertEquals("RUNNING", status(dataSource, id));
    }

    @Test
    void recoverRunsTheReconcilerForTheRowAndReturnsItsCounts() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OPERATE, "recover-1", id, "recover").statusCode(200)
                .body("outcome", equalTo("recover-requested"))
                .body("reconciler", notNullValue());

        assertEquals(1, historyWithReason(dataSource, id, "operator.recover"));
        assertEquals(OperationsSupport.OPERATOR, historyActor(dataSource, id, "operator.recover"));
        assertEquals("RUNNING", status(dataSource, id));
    }

    @Test
    void recoverOfATerminalInstanceIsRefused() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);
        completeWithoutEngine(tenant, id);

        post(tenant, OPERATE, "recover-done", id, "recover").statusCode(409)
                .body("detail", containsString("terminal"));
        assertEquals(0, historyWithReason(dataSource, id, "operator.recover"));
    }

    @Test
    void unknownMalformedAndAnotherTenantsIdsAreNotFound() {
        String owner = LifecycleSupport.newTenant();
        String other = LifecycleSupport.newTenant();
        String id = startRunning(owner);

        post(other, OPERATE, "other-1", id, "cancel").statusCode(404);
        post(owner, OPERATE, "unknown-1", UNKNOWN_ID, "cancel").statusCode(404);
        post(owner, OPERATE, "malformed-1", "not-a-ulid", "cancel").statusCode(404);

        assertEquals(0, historyWithReason(dataSource, id, "operator.cancel"));
        assertEquals("RUNNING", status(dataSource, id));
        assertEquals(1, auditCount(dataSource, "operation.result", id, "not-found"),
                "another tenant's call is audited as not-found and reveals nothing");
    }

    @Test
    void theAuditOfAnUnknownIdCarriesTheCallersTenant() {
        String tenant = LifecycleSupport.newTenant();
        post(tenant, OPERATE, "unknown-audit", UNKNOWN_ID, "cancel").statusCode(404);
        assertEquals(1, LifecycleSupport.count(dataSource,
                "select count(*) from audit.event where event_type = 'operation.result' and instance_id = ?"
                        + " and attributes ->> 'oa.tenant' = ?",
                UNKNOWN_ID, tenant));
    }

    /** Moves a RUNNING row to COMPLETED through the registry, as the engine observer would. */
    private void completeWithoutEngine(String tenant, String instanceId) {
        InstanceRecord row = store.find(tenant, Ulid.parse(instanceId)).orElseThrow();
        store.replace(row.successor(Instant.now().truncatedTo(ChronoUnit.MICROS), InstanceStatus.COMPLETED,
                        row.engineInstanceId(), row.startAttempts(), null, null),
                row.version(), "test.complete", InstanceService.RUNTIME_ACTOR);
    }
}
