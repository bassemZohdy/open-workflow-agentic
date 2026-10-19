package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.INSPECT;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.OPERATE;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.OPERATOR;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.auditCount;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.historyActor;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.historyWithReason;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.inspect;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.moveTo;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.outboxWithReason;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.retriesOf;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.scalar;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.startRunning;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.status;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.LiveRetryExistsException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #24 (a cancel of a row with no engine instance id goes straight to CANCELLED) and issue #23 (a retry of a FAILED
 * instance creates a new linked instance; ADR 0019, amendments 3 and 4). Each test uses its own tenant.
 */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class RetryAndDirectCancelTest {

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

    // ---- issue #24: cancel with no engine instance id ---------------------------------------------------------------

    @Test
    void aCancelOfAStartingRowWithNoEngineIdGoesStraightToCancelled() throws Exception {
        String tenant = newTenant();
        String key = newKey();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        engine.holdNextPrepare(entered, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> start = pool.submit(() ->
                    LifecycleSupport.post(tenant, LifecycleSupport.standardBody(key)).extract().response());
            assertTrue(entered.await(60, TimeUnit.SECONDS), "the start reached the engine prepare");
            String id = scalar(dataSource,
                    "select id from task.instance where tenant = ? and idempotency_key = ?", tenant, key);
            assertEquals("STARTING", status(dataSource, id));
            assertNull(scalar(dataSource, "select engine_instance_id from task.instance where id = ?", id));

            post(tenant, OPERATE, "direct-cancel-1", id, "cancel").statusCode(200)
                    .body("outcome", equalTo("cancelled"))
                    .body("instance.status", equalTo("CANCELLED"));

            assertEquals("CANCELLED", status(dataSource, id));
            assertEquals("STARTING", scalar(dataSource,
                    "select from_status from task.instance_event where instance_id = ? and reason = 'operator.cancel'", id));
            assertEquals("CANCELLED", scalar(dataSource,
                    "select to_status from task.instance_event where instance_id = ? and reason = 'operator.cancel'", id));
            assertEquals(OPERATOR, historyActor(dataSource, id, "operator.cancel"));
            assertEquals(1, outboxWithReason(dataSource, id, "operator.cancel"), "the outbox row commits with it");
            assertEquals(1, auditCount(dataSource, "operation.attempt", id, "allowed"));
            assertEquals(1, auditCount(dataSource, "operation.result", id, "cancelled"));

            release.countDown();
            Response response = start.get(60, TimeUnit.SECONDS);
            assertEquals(202, response.statusCode(), response.asString());
            assertEquals("CANCELLED", response.path("status"));
            assertEquals(0, engine.starts(), "the prepared engine instance is never started");
            assertEquals(1, engine.prepares());
            assertEquals("CANCELLED", status(dataSource, id), "the late start does not undo the cancel");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCancelOfAStartingRowWithAnEngineIdStillGoesThroughCancelRequested() {
        String tenant = newTenant();
        engine.failNextStarts(1);
        String id = LifecycleSupport.post(tenant, LifecycleSupport.standardBody(newKey()))
                .statusCode(202)
                .body("status", equalTo("STARTING"))
                .extract().path("id");
        assertNotNull(scalar(dataSource, "select engine_instance_id from task.instance where id = ?", id),
                "the failed start has recorded the engine instance id");

        post(tenant, OPERATE, "engine-cancel-1", id, "cancel").statusCode(202)
                .body("outcome", equalTo("cancel-requested"));

        assertEquals("CANCEL_REQUESTED", scalar(dataSource,
                "select to_status from task.instance_event where instance_id = ? and reason = 'operator.cancel'", id));
        assertTrue(Set.of("CANCEL_REQUESTED", "CANCELLED").contains(status(dataSource, id)), status(dataSource, id));
    }

    // ---- issue #23: retry of a FAILED instance ------------------------------------------------------------------------

    @Test
    void aRetryOfAFailedInstanceCreatesALinkedInstanceWithTheSameDefinitionAndInput() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        engine.reset();

        String created = post(tenant, OPERATE, "retry-failed-1", old, "retry").statusCode(202)
                .body("outcome", equalTo("retry-created"))
                .body("retriedFrom", equalTo(old))
                .body("instance.id", not(equalTo(old)))
                .body("instance.retriedFrom", equalTo(old))
                .body("instance.definitionId", equalTo(LifecycleSupport.DEFINITION_ID))
                .body("instance.definitionVersion", equalTo(LifecycleSupport.DEFINITION_VERSION))
                .body("instance.status", anyOf(equalTo("RUNNING"), equalTo("WAITING")))
                .body("instance.startAttempts", equalTo(0))
                .extract().path("instance.id");

        assertEquals(1, engine.starts(), "the new instance is started through the normal start path");
        assertEquals(old, scalar(dataSource, "select retried_from from task.instance where id = ?", created));
        assertEquals("FAILED", status(dataSource, old), "the failed instance stays terminal");
        assertEquals(scalar(dataSource, "select tenant from task.instance where id = ?", old),
                scalar(dataSource, "select tenant from task.instance where id = ?", created));
        assertEquals(scalar(dataSource, "select owner_id from task.instance where id = ?", old),
                scalar(dataSource, "select owner_id from task.instance where id = ?", created));
        assertEquals(scalar(dataSource, "select input::text from task.instance where id = ?", old),
                scalar(dataSource, "select input::text from task.instance where id = ?", created));
        String newKey = scalar(dataSource, "select idempotency_key from task.instance where id = ?", created);
        assertTrue(newKey.startsWith("retry-"), newKey);
        assertTrue(newKey.matches("[A-Za-z0-9._:-]{1,128}"), newKey);
    }

    @Test
    void theNewInstanceAndTheOldOneAreLinkedInBothDirectionsInInspection() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        String created = post(tenant, OPERATE, "retry-links-1", old, "retry").statusCode(202)
                .extract().path("instance.id");

        inspect(tenant, INSPECT, old).statusCode(200)
                .body("instance.retriedFrom", equalTo(null))
                .body("retries", hasItem(created));
        inspect(tenant, INSPECT, created).statusCode(200)
                .body("instance.retriedFrom", equalTo(old))
                .body("retries", equalTo(java.util.List.of()));
    }

    @Test
    void theRetryIsRecordedWithTheOperatorAsActorAndTheOutboxRowCommitsWithIt() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        String created = post(tenant, OPERATE, "retry-audit-1", old, "retry").statusCode(202)
                .extract().path("instance.id");

        assertEquals(1, historyWithReason(dataSource, created, "operator.retry"));
        assertEquals(OPERATOR, historyActor(dataSource, created, "operator.retry"),
                "the new instance keeps the owner, but its first change names the operator");
        assertEquals(1, outboxWithReason(dataSource, created, "operator.retry"));
        assertEquals(1, auditCount(dataSource, "operation.attempt", old, "allowed"));
        assertEquals(1, auditCount(dataSource, "operation.result", old, "retry-created"));
        assertEquals(0, historyWithReason(dataSource, old, "operator.retry"), "the failed row is not changed");
    }

    @Test
    void aRepeatOfTheSameRetryCallReturnsTheSameInstanceAndStartsNothingAgain() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        engine.reset();

        String first = post(tenant, OPERATE, "retry-same-1", old, "retry").statusCode(202)
                .extract().path("instance.id");
        post(tenant, OPERATE, "retry-same-1", old, "retry").statusCode(202)
                .body("instance.id", equalTo(first));

        assertEquals(1, engine.starts(), "the repeat starts nothing");
        assertEquals(1, retriesOf(dataSource, old));
        assertEquals(1, auditCount(dataSource, "operation.result", old, "replayed"));
    }

    @Test
    void theDerivedKeyMakesTheRetryIdempotentBelowTheOperationsLayer() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        engine.reset();
        InstanceRecord failed = store.find(tenant, Ulid.parse(old)).orElseThrow();
        Subject operator = new Subject(SubjectKind.USER, OPERATOR);

        StartResult first = service.retryFailed(failed, operator, "derived-call-1");
        StartResult repeat = service.retryFailed(failed, operator, "derived-call-1");

        assertTrue(first.created());
        assertEquals(false, repeat.created(), "a repeat returns the existing row");
        assertEquals(first.record().id(), repeat.record().id());
        assertEquals(1, engine.starts(), "the repeat starts nothing");
        assertEquals(1, retriesOf(dataSource, old));
    }

    @Test
    void aSecondRetryWhileTheFirstIsLiveIsRefusedAndCreatesNothing() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        String first = post(tenant, OPERATE, "retry-live-a", old, "retry").statusCode(202)
                .extract().path("instance.id");

        post(tenant, OPERATE, "retry-live-b", old, "retry").statusCode(409)
                .body("detail", containsString("not terminal"));

        assertEquals(1, retriesOf(dataSource, old), "one live retry per failed instance");
        assertEquals(1, auditCount(dataSource, "operation.result", old, "refused-live-retry"));
        assertEquals(0, historyWithReason(dataSource, old, "operator.retry"));
        assertNotNull(first);
    }

    @Test
    void aRetryIsAllowedAgainOnceTheFirstRetryIsTerminal() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        String first = post(tenant, OPERATE, "retry-after-a", old, "retry").statusCode(202)
                .extract().path("instance.id");
        moveTo(store, tenant, first, InstanceStatus.FAILED);

        post(tenant, OPERATE, "retry-after-b", old, "retry").statusCode(202)
                .body("outcome", equalTo("retry-created"));

        assertEquals(2, retriesOf(dataSource, old));
    }

    @Test
    void retryOfAnyOtherStateIsRefusedAndCreatesNothing() {
        String tenant = newTenant();

        String running = startRunning(tenant);
        String waiting = startRunning(tenant);
        moveTo(store, tenant, waiting, InstanceStatus.WAITING);
        String cancelRequested = startRunning(tenant);
        moveTo(store, tenant, cancelRequested, InstanceStatus.CANCEL_REQUESTED);
        String completed = startRunning(tenant);
        moveTo(store, tenant, completed, InstanceStatus.COMPLETED);
        String cancelled = startRunning(tenant);
        moveTo(store, tenant, cancelled, InstanceStatus.CANCEL_REQUESTED);
        moveTo(store, tenant, cancelled, InstanceStatus.CANCELLED);

        for (String id : new String[] {running, waiting, cancelRequested, completed, cancelled}) {
            post(tenant, OPERATE, "retry-other-" + id, id, "retry").statusCode(409)
                    .body("detail", containsString("STARTING"));
            assertEquals(0, retriesOf(dataSource, id), "no retry of " + status(dataSource, id));
        }
    }

    /**
     * The database backstop of the one-live-retry rule (V50, instance_one_live_retry_uk): with the service's check
     * bypassed, a second live retry of the same failed instance is refused by the store itself.
     */
    @Test
    void theStoreRefusesASecondLiveRetryEvenWhenTheServiceCheckIsBypassed() {
        String tenant = newTenant();
        String old = startRunning(tenant);
        moveTo(store, tenant, old, InstanceStatus.FAILED);
        InstanceRecord failed = store.find(tenant, Ulid.parse(old)).orElseThrow();
        Subject operator = new Subject(SubjectKind.USER, OPERATOR);
        StartRequest first = new StartRequest(tenant, failed.owner(), failed.definition(), failed.input(),
                "bypass-a", failed.id());
        StartRequest second = new StartRequest(tenant, failed.owner(), failed.definition(), failed.input(),
                "bypass-b", failed.id());

        store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), first,
                Instant.now().truncatedTo(ChronoUnit.MICROS)), "operator.retry", operator);
        assertThrows(LiveRetryExistsException.class, () -> store.insertIfAbsent(
                InstanceRecord.starting(UlidGenerator.shared().next(), second,
                        Instant.now().truncatedTo(ChronoUnit.MICROS)), "operator.retry", operator));

        assertEquals(1, retriesOf(dataSource, old));
    }

    @Test
    void aRetryOfAFailedInstanceWithADifferentKeyForAnotherInstanceIsNotLinked() {
        String tenant = newTenant();
        String a = startRunning(tenant);
        String b = startRunning(tenant);
        moveTo(store, tenant, a, InstanceStatus.FAILED);
        moveTo(store, tenant, b, InstanceStatus.FAILED);

        String createdForA = post(tenant, OPERATE, "retry-pair-a", a, "retry").statusCode(202)
                .extract().path("instance.id");

        assertEquals(1, retriesOf(dataSource, a));
        assertEquals(0, retriesOf(dataSource, b), "a retry links only to the instance it names");
        assertEquals(a, scalar(dataSource, "select retried_from from task.instance where id = ?", createdForA));
    }
}
