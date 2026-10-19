package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.restassured.RestAssured.given;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.restassured.response.ValidatableResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;

/** Helpers for the operations API tests (WP-105d): calls with headers, and SQL checks of history, outbox and audit. */
final class OperationsSupport {

    static final String OPERATOR = "ops-lead";
    static final String INSPECT = "oa.inspect";
    static final String OPERATE = "oa.operate";
    static final String RECOVER = "oa.recover";

    private OperationsSupport() {
    }

    static ValidatableResponse inspect(String tenant, String scopes, String id) {
        var request = given().header("X-Tenant", tenant).header("X-Subject", OPERATOR);
        if (scopes != null) {
            request = request.header("X-Scopes", scopes);
        }
        return request.when().get("/operations/instances/" + id).then();
    }

    /** A mutating call: POST {@code /operations/instances/{id}/{action}}. A null key sends no Idempotency-Key header. */
    static ValidatableResponse post(String tenant, String scopes, String key, String id, String action) {
        var request = given().header("X-Tenant", tenant).header("X-Subject", OPERATOR);
        if (scopes != null) {
            request = request.header("X-Scopes", scopes);
        }
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.contentType("application/json").when().post("/operations/instances/" + id + "/" + action).then();
    }

    /** Starts a probe instance (it stays RUNNING for the test) and returns its id. */
    static String startRunning(String tenant) {
        return LifecycleSupport.post(tenant, LifecycleSupport.standardBody(LifecycleSupport.newKey()))
                .statusCode(202).extract().path("id");
    }

    /** The single text value a query returns, or null. */
    static String scalar(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    /** Audit events of one action for one instance with one outcome. */
    static long auditCount(DataSource dataSource, String action, String instanceId, String outcome) {
        return LifecycleSupport.count(dataSource,
                "select count(*) from audit.event where event_type = ? and instance_id = ? and attributes ->> 'oa.outcome' = ?",
                action, instanceId, outcome);
    }

    /** Audit events of any action naming this instance. */
    static long auditEvents(DataSource dataSource, String instanceId) {
        return LifecycleSupport.count(dataSource, "select count(*) from audit.event where instance_id = ?", instanceId);
    }

    /** History rows of an instance with this reason. */
    static long historyWithReason(DataSource dataSource, String instanceId, String reason) {
        return LifecycleSupport.count(dataSource,
                "select count(*) from task.instance_event where instance_id = ? and reason = ?", instanceId, reason);
    }

    /** The actor id of the history row with this reason. */
    static String historyActor(DataSource dataSource, String instanceId, String reason) {
        return scalar(dataSource,
                "select actor_id from task.instance_event where instance_id = ? and reason = ? order by version desc limit 1",
                instanceId, reason);
    }

    /** Outbox rows of an instance whose change carries this reason. */
    static long outboxWithReason(DataSource dataSource, String instanceId, String reason) {
        return LifecycleSupport.count(dataSource,
                "select count(*) from task.outbox where aggregate_id = ? and payload ->> 'reason' = ?", instanceId, reason);
    }

    /** The registry status of an instance. */
    static String status(DataSource dataSource, String instanceId) {
        return scalar(dataSource, "select status from task.instance where id = ?", instanceId);
    }

    /** How many instances were created to retry this one (ADR 0019, amendment 3). */
    static long retriesOf(DataSource dataSource, String instanceId) {
        return LifecycleSupport.count(dataSource, "select count(*) from task.instance where retried_from = ?", instanceId);
    }

    /**
     * Moves a row to another status through the registry, as the engine observer or the reconciler would, keeping its
     * engine instance id. The reconciler is off in the boot tests, so the row stays where the test puts it.
     */
    static void moveTo(JdbcInstanceStore store, String tenant, String instanceId, InstanceStatus status) {
        InstanceRecord row = store.find(tenant, Ulid.parse(instanceId)).orElseThrow();
        store.replace(row.successor(Instant.now().truncatedTo(ChronoUnit.MICROS), status, row.engineInstanceId(),
                        row.startAttempts(), row.lastError(), null),
                row.version(), "test.move", InstanceService.RUNTIME_ACTOR);
    }
}
