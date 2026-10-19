package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.OPERATE;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.RECOVER;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.auditCount;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.historyWithReason;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.inspect;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.startRunning;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Degraded mode of the operations API (WP-103 section 6, WP-105d): with the main policy unavailable, an operator holding
 * the recovery scope can cancel and pause a task, and every other operation is refused with 503 and changes nothing.
 */
@QuarkusTest
@TestProfile(DegradedOperatorProfile.class)
class OperationsDegradedTest {

    @Inject
    DataSource dataSource;

    @Test
    void cancelWithTheRecoveryScopeIsAllowedAndAuditedAsARecoveryDecision() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OperationsSupport.RECOVER, "degraded-cancel", id, "cancel").statusCode(202)
                .body("outcome", equalTo("cancel-requested"));

        assertEquals(1, historyWithReason(dataSource, id, "operator.cancel"));
        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "allowed"));
        assertTrue(LifecycleSupport.count(dataSource,
                "select count(*) from audit.event where event_type = 'policy.decision' and resource_id = 'operation:cancel'"
                        + " and attributes ->> 'oa.recovery-path' = 'true' and attributes ->> 'oa.outcome' = 'ALLOW'") >= 1,
                "the recovery allow is a recorded decision");
    }

    @Test
    void pauseWithTheRecoveryScopeIsAuthorizedAndNotImplemented() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OperationsSupport.RECOVER, "degraded-pause", id, "pause").statusCode(501);

        assertEquals("RUNNING", status(dataSource, id));
        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "allowed"));
    }

    @Test
    void retryIsRefusedWithTheEngineDownAndChangesNothing() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OperationsSupport.RECOVER, "degraded-retry", id, "retry").statusCode(503)
                .contentType("application/problem+json")
                .body("detail", containsString("nothing was changed"));

        assertEquals(0, historyWithReason(dataSource, id, "operator.retry"));
        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "error"));
        assertEquals("RUNNING", status(dataSource, id));
    }

    @Test
    void recoverIsRefusedWithTheEngineDownAndChangesNothing() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, RECOVER, "degraded-recover", id, "recover").statusCode(503);

        assertEquals(0, historyWithReason(dataSource, id, "operator.recover"));
        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "error"));
    }

    @Test
    void inspectIsRefusedWithTheEngineDown() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        inspect(tenant, OperationsSupport.RECOVER, id).statusCode(503)
                .body("status", equalTo(503));

        assertEquals(0, auditCount(dataSource, "operation.result", id, "inspected"));
        assertEquals(1, auditCount(dataSource, "operation.attempt", id, "error"));
    }

    @Test
    void cancelWithoutTheRecoveryScopeIsRefusedWithTheEngineDown() {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);

        post(tenant, OPERATE, "degraded-plain-cancel", id, "cancel").statusCode(503);

        assertEquals("RUNNING", status(dataSource, id));
        assertEquals(0, historyWithReason(dataSource, id, "operator.cancel"));
    }
}
