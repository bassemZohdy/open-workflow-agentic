package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.count;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.get;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.standardBody;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.HistoryEntry;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When the engine does not confirm a start, the row must not stay invisible. It stays STARTING, with the engine id
 * already recorded and the attempt counted, so the reconciler (WP-105c) can find it. A repeated request does not
 * start it again.
 */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class EngineFailureTest {

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
    void aFailedStartLeavesAFindableStartingRowWithItsEngineIdAndAttempt() {
        String tenant = newTenant();
        String key = newKey();
        engine.failNextStarts(1);

        var response = post(tenant, standardBody(key))
                .statusCode(202)
                .body("status", equalTo("STARTING"))
                .body("startAttempts", equalTo(1))
                .body("engineInstanceId", notNullValue())
                .extract().response();
        String id = response.path("id");

        assertEquals(1, engine.prepares());
        assertEquals(1, engine.starts(), "the engine start was attempted and failed");

        InstanceRecord row = store.find(tenant, Ulid.parse(id)).orElseThrow();
        assertEquals(InstanceStatus.STARTING, row.status());
        assertEquals(1, row.startAttempts());
        assertEquals("IllegalStateException", row.lastError(), "the class of the error, never its message");
        assertNotNull(row.engineInstanceId());
        assertEquals(response.path("engineInstanceId"), row.engineInstanceId());

        HistoryEntry last = service.history(row.id()).get(2);
        assertEquals("engine.start.failed", last.reason());
        assertEquals(InstanceStatus.STARTING, last.fromStatus());
        assertEquals(InstanceStatus.STARTING, last.toStatus());
    }

    @Test
    void aRepeatedRequestDoesNotStartAFailedInstanceAgain() {
        String tenant = newTenant();
        String key = newKey();
        engine.failNextStarts(1);
        String id = post(tenant, standardBody(key)).statusCode(202).extract().path("id");

        get(tenant, id).statusCode(200).body("status", equalTo("STARTING"));
        post(tenant, standardBody(key))
                .statusCode(200)
                .body("id", equalTo(id))
                .body("status", equalTo("STARTING"));

        assertEquals(1, engine.starts(), "the repeat must not call the engine");
        assertEquals(1L, count(dataSource,
                "select count(*) from task.instance where tenant = ? and status = 'STARTING'", tenant),
                "the reconciler can find the row by its status");
    }
}
