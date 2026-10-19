package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.OPERATE;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.historyWithReason;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.startRunning;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.OperationsSupport.status;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Concurrent operations on one instance serialize (WP-105d): the row lock and the version check of the registry make a
 * stale call a 409, the idempotency reservation makes two identical calls one operation, and a cancel is never undone.
 */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class OperationsConcurrencyTest {

    @Inject
    CountingWorkflowEngine engine;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void resetEngine() {
        engine.reset();
    }

    @Test
    void manyCancelsWithDifferentKeysApplyTheCancelOnce() throws Exception {
        String tenant = LifecycleSupport.newTenant();
        String id = startRunning(tenant);
        int callers = 8;

        List<Integer> codes = runTogether(callers, i -> () -> post(tenant, OPERATE, "race-" + i, id, "cancel")
                .extract().statusCode());

        assertEquals(1, codes.stream().filter(c -> c == 202).count(), codes.toString());
        assertTrue(Set.of(200, 202, 409).containsAll(codes), codes.toString());
        assertEquals(1, historyWithReason(dataSource, id, "operator.cancel"), "one history row for the cancel");
        assertTrue(Set.of("CANCEL_REQUESTED", "CANCELLED").contains(status(dataSource, id)), status(dataSource, id));
    }

    @Test
    void aCancelRacingARetryNeverUndoesTheCancel() throws Exception {
        String tenant = LifecycleSupport.newTenant();
        engine.failNextStarts(1);
        String id = LifecycleSupport.post(tenant, LifecycleSupport.standardBody(LifecycleSupport.newKey()))
                .statusCode(202).extract().path("id");

        List<Integer> codes = runTogether(2, i -> () -> post(tenant, OPERATE, (i == 0 ? "cancel-" : "retry-") + i,
                id, i == 0 ? "cancel" : "retry").extract().statusCode());

        int cancel = codes.get(0);
        int retry = codes.get(1);
        assertTrue(Set.of(200, 202, 409).contains(cancel), codes.toString());
        assertTrue(Set.of(202, 409).contains(retry), codes.toString());
        String finalStatus = status(dataSource, id);
        if (cancel == 202) {
            assertTrue(Set.of("CANCEL_REQUESTED", "CANCELLED", "COMPLETED", "FAILED").contains(finalStatus),
                    "a cancel that was accepted is never undone: " + finalStatus);
        }
        assertTrue(historyWithReason(dataSource, id, "operator.cancel") <= 1);
    }

    /**
     * A cancel lands while the start is between its insert and its engine id. The start does not run the instance: its
     * change of version is refused, so it returns the row as the operator left it, and the engine is never started.
     */
    @Test
    void aCancelThatLandsWhileTheStartIsPreparedStopsTheStartBeforeItRuns() throws Exception {
        String tenant = LifecycleSupport.newTenant();
        String key = LifecycleSupport.newKey();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        engine.holdNextPrepare(entered, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> start = pool.submit(() ->
                    LifecycleSupport.post(tenant, LifecycleSupport.standardBody(key)).extract().response());
            assertTrue(entered.await(60, TimeUnit.SECONDS), "the start reached the engine prepare");
            String id = OperationsSupport.scalar(dataSource,
                    "select id from task.instance where tenant = ? and idempotency_key = ?", tenant, key);
            assertEquals("STARTING", status(dataSource, id));

            // No engine instance id is recorded yet, so the cancel is direct (ADR 0019, amendment 4).
            post(tenant, OPERATE, "cancel-during-prepare", id, "cancel").statusCode(200)
                    .body("outcome", equalTo("cancelled"));
            release.countDown();

            Response response = start.get(60, TimeUnit.SECONDS);
            assertEquals(202, response.statusCode(), response.asString());
            assertEquals("CANCELLED", response.path("status"));
            assertEquals(0, engine.starts(), "the prepared instance is never started");
            assertEquals("CANCELLED", status(dataSource, id));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs one task per caller at the same moment and returns the status codes in caller order. */
    private static List<Integer> runTogether(int callers, java.util.function.IntFunction<Callable<Integer>> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<Integer> call = task.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return call.call();
                }));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get());
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }
}
