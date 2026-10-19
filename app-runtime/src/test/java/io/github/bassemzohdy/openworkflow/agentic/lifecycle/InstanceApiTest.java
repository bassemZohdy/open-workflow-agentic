package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.restassured.RestAssured.given;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.body;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.count;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.execute;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.get;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.standardBody;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.HistoryEntry;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.IllegalTransitionException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StaleVersionException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The instance API and the registry on Postgres, with the test engine that delegates to the real Flow adapter. */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class InstanceApiTest {

    @Inject
    CountingWorkflowEngine engine;

    @Inject
    JdbcInstanceStore store;

    @Inject
    InstanceService service;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void resetEngine() {
        engine.reset();
    }

    @Test
    void startAcceptsWithLocationAndRecordsTheRunningInstance() {
        String tenant = newTenant();
        String key = newKey();

        var response = post(tenant, standardBody(key))
                .statusCode(202)
                .body("status", equalTo("RUNNING"))
                .body("definitionId", equalTo(LifecycleSupport.DEFINITION_ID))
                .body("definitionVersion", equalTo(LifecycleSupport.DEFINITION_VERSION))
                .body("engineInstanceId", notNullValue())
                .extract().response();
        String id = response.path("id");
        assertTrue(response.header("Location").endsWith("/instances/" + id), response.header("Location"));

        assertEquals(1, engine.prepares());
        assertEquals(1, engine.starts());
        get(tenant, id).statusCode(200).body("status", equalTo("RUNNING")).body("id", equalTo(id));
    }

    @Test
    void theHistoryHoldsOneRowPerChangeInOrder() {
        String tenant = newTenant();
        String id = startedId(tenant, newKey());

        List<HistoryEntry> history = service.history(Ulid.parse(id));
        assertEquals(3, history.size(), history.toString());
        assertEquals(List.of(0L, 1L, 2L), history.stream().map(HistoryEntry::version).toList());
        assertNull(history.get(0).fromStatus());
        assertEquals(InstanceStatus.STARTING, history.get(0).toStatus());
        assertEquals("start.requested", history.get(0).reason());
        assertEquals(InstanceStatus.STARTING, history.get(1).toStatus());
        assertEquals("engine.instance.prepared", history.get(1).reason());
        assertEquals(InstanceStatus.RUNNING, history.get(2).toStatus());
        // Either the start path or the engine observer records RUNNING first (WP-105c); both are the runtime's writes.
        assertTrue(List.of("engine.start.accepted", "engine.observed.RUNNING").contains(history.get(2).reason()),
                history.get(2).reason());
        assertEquals("agentic-runtime", history.get(2).actor().id());
    }

    @Test
    void aRepeatedRequestReturnsTheSameInstanceAndStartsNothing() {
        String tenant = newTenant();
        String key = newKey();
        String first = startedId(tenant, key);
        assertEquals(1, engine.starts());

        post(tenant, standardBody(key))
                .statusCode(200)
                .body("id", equalTo(first))
                .body("status", equalTo("RUNNING"));

        assertEquals(1, engine.prepares(), "a repeat must not prepare another engine instance");
        assertEquals(1, engine.starts(), "a repeat must not start the engine again");
        assertEquals(1L, count(dataSource, "select count(*) from task.instance where tenant = ? and idempotency_key = ?",
                tenant, key));
    }

    @Test
    void theSameKeyForADifferentRequestIsAConflictAndChangesNothing() {
        String tenant = newTenant();
        String key = newKey();
        String first = startedId(tenant, key);

        post(tenant, body(LifecycleSupport.DEFINITION_ID, LifecycleSupport.DEFINITION_VERSION, key,
                Map.of("topic", "a different topic")))
                .statusCode(409);

        assertEquals(1, engine.starts());
        get(tenant, first).body("status", equalTo("RUNNING"));
    }

    @Test
    void anUnknownDefinitionIsRejectedAndNoRowIsCreated() {
        String tenant = newTenant();
        String key = newKey();

        post(tenant, body("agentic/no-such-definition", "9.9.9", key, Map.of()))
                .statusCode(422);

        assertEquals(0L, count(dataSource, "select count(*) from task.instance where tenant = ?", tenant));
        assertEquals(0, engine.prepares());
    }

    @Test
    void aMalformedRequestIsBadRequest() {
        String tenant = newTenant();
        post(tenant, body("no-namespace-separator", LifecycleSupport.DEFINITION_VERSION, newKey(), Map.of()))
                .statusCode(400)
                .contentType("application/problem+json");
        post(tenant, body(LifecycleSupport.DEFINITION_ID, LifecycleSupport.DEFINITION_VERSION, "bad key!", Map.of()))
                .statusCode(400);
        given().header("X-Tenant", tenant).header("X-Subject", "alice").contentType("application/json")
                .body("not an object").when().post("/instances").then().statusCode(400);
        assertEquals(0, engine.prepares());
    }

    @Test
    void anotherTenantCannotReadTheInstanceAndTheIdDoesNotLeak() {
        String owner = newTenant();
        String other = newTenant();
        String id = startedId(owner, newKey());

        get(other, id).statusCode(404);
        get(owner, id).statusCode(200);
    }

    @Test
    void unknownAndMalformedIdsAreNotFound() {
        String tenant = newTenant();
        get(tenant, "01ARZ3NDEKTSV4RRFFQ69G5FAV").statusCode(404);
        get(tenant, "not-a-ulid").statusCode(404);
    }

    @Test
    void theApiRefusesARequestWithoutCallerHeaders() {
        given().contentType("application/json").body(standardBody(newKey()))
                .when().post("/instances").then().statusCode(401);
        given().when().get("/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV").then().statusCode(401);
    }

    @Test
    void anIllegalTransitionIsRejectedAndLeavesNoHistory() {
        String tenant = newTenant();
        InstanceRecord row = store.find(tenant, Ulid.parse(startedId(tenant, newKey()))).orElseThrow();
        InstanceRecord completed = store.replace(
                row.successor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                        InstanceStatus.COMPLETED, row.engineInstanceId(), row.startAttempts(), null,
                        row.waitDeadline()),
                row.version(), "test.complete", InstanceService.RUNTIME_ACTOR);
        int historyBefore = service.history(row.id()).size();

        InstanceRecord back = completed.successor(Instant.now(), InstanceStatus.RUNNING,
                completed.engineInstanceId(), completed.startAttempts(), null, null);
        assertThrows(IllegalTransitionException.class,
                () -> store.replace(back, completed.version(), "test.illegal", InstanceService.RUNTIME_ACTOR));

        assertEquals(historyBefore, service.history(row.id()).size(), "a refused change writes no history");
        assertEquals(InstanceStatus.COMPLETED, store.find(tenant, row.id()).orElseThrow().status());
    }

    @Test
    void aStaleVersionIsRejectedAndNothingIsWritten() {
        String tenant = newTenant();
        InstanceRecord row = store.find(tenant, Ulid.parse(startedId(tenant, newKey()))).orElseThrow();
        InstanceRecord stale = row.successor(Instant.now(), InstanceStatus.RUNNING, row.engineInstanceId(),
                row.startAttempts(), null, null);
        // A later change moves the row on; the first caller still holds the old version.
        store.replace(stale, row.version(), "test.first", InstanceService.RUNTIME_ACTOR);

        // The second caller still holds the old row, so its change is built from that row (version + 1).
        InstanceRecord second = row.successor(Instant.now(), InstanceStatus.RUNNING, row.engineInstanceId(),
                row.startAttempts(), null, null);
        assertThrows(StaleVersionException.class,
                () -> store.replace(second, row.version(), "test.stale", InstanceService.RUNTIME_ACTOR));
        assertEquals(stale.version(), store.find(tenant, row.id()).orElseThrow().version());
    }

    @Test
    void identityAndInputMayNotBeChangedByAReplace() {
        String tenant = newTenant();
        InstanceRecord row = store.find(tenant, Ulid.parse(startedId(tenant, newKey()))).orElseThrow();
        InstanceRecord renamed = new InstanceRecord(row.id(), tenant, row.owner(), row.idempotencyKey(),
                row.requestFingerprint(), row.definition(), Map.of("topic", "changed"), row.status(),
                row.engineInstanceId(), row.startAttempts(), null, null, row.retriedFrom(), row.version() + 1,
                row.createdAt(), Instant.now());
        assertThrows(IllegalStateException.class,
                () -> store.replace(renamed, row.version(), "test.identity", InstanceService.RUNTIME_ACTOR));
    }

    @Test
    void historyIsAppendOnlyInTheDatabase() {
        String tenant = newTenant();
        String id = startedId(tenant, newKey());

        SQLException update = execute(dataSource,
                "update task.instance_event set reason = 'rewritten' where instance_id = '" + id + "'");
        assertNotNull(update, "an update of history must be refused");
        assertTrue(update.getMessage().contains("append-only"), update.getMessage());

        SQLException delete = execute(dataSource, "delete from task.instance_event where instance_id = '" + id + "'");
        assertNotNull(delete, "a delete of history must be refused");

        SQLException truncate = execute(dataSource, "truncate task.instance_event");
        assertNotNull(truncate, "a truncate of history must be refused");

        assertEquals(3L, count(dataSource, "select count(*) from task.instance_event where instance_id = ?", id));
    }

    @Test
    void theInstanceEntityCarriesTheVersionAndTheTimes() {
        String tenant = newTenant();
        String id = startedId(tenant, newKey());
        get(tenant, id)
                .body("version", equalTo(2))
                .body("startAttempts", equalTo(0))
                .body("createdAt", notNullValue())
                .body("updatedAt", notNullValue());
    }

    /** Starts an instance through the API and returns its id. */
    private String startedId(String tenant, String key) {
        return post(tenant, standardBody(key)).statusCode(202).extract().path("id");
    }
}
