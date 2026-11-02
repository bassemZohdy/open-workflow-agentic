package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.count;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.post;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.standardBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two or more identical start requests at the same moment produce one instance and one engine start (ADR 0019,
 * Start). Twenty threads release together through a barrier, so the inserts really race.
 */
@QuarkusTest
@TestProfile(TrustedCallerProfile.class)
class ConcurrentStartTest {

    private static final int THREADS = 20;

    @Inject
    CountingWorkflowEngine engine;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void resetEngine() {
        engine.reset();
    }

    @Test
    void twentyIdenticalConcurrentStartsCreateOneInstanceAndOneEngineStart() throws Exception {
        String tenant = newTenant();
        String key = newKey();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    var response = post(tenant, standardBody(key)).extract().response();
                    return new Outcome(response.statusCode(), response.path("id"));
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }

            long created = outcomes.stream().filter(o -> o.status() == 202).count();
            long repeated = outcomes.stream().filter(o -> o.status() == 200).count();
            Set<String> ids = new java.util.HashSet<>();
            outcomes.forEach(o -> ids.add(o.id()));

            assertEquals(1, created, outcomes.toString());
            assertEquals(THREADS - 1, repeated, outcomes.toString());
            assertEquals(1, ids.size(), "all requests must see the same instance: " + ids);
            assertEquals(1, engine.prepares(), "exactly one engine instance is prepared");
            assertEquals(1, engine.starts(), "exactly one engine start is made");
            assertEquals(1L, count(dataSource,
                    "select count(*) from task.instance where tenant = ? and idempotency_key = ?", tenant, key));
            String id = ids.iterator().next();
            assertTrue(id.matches("[0-7][0-9A-HJKMNP-TV-Z]{25}"), id);
            assertEquals(3L, count(dataSource, "select count(*) from task.instance_event where instance_id = ?", id),
                    "one insert and two changes, nothing more");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void competingOwnersWithTheSameKeyCannotSeeEachOthersInstance() throws Exception {
        String tenant = newTenant(); String key = newKey();
        var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (String subject : List.of("alice", "bob")) {
                futures.add(pool.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    var response = io.restassured.RestAssured.given().header("X-Tenant", tenant)
                            .header("X-Subject", subject).header("X-Scopes", "oa.operate")
                            .contentType("application/json").body(standardBody(key)).when().post("/instances").then().extract().response();
                    return new Outcome(response.statusCode(), response.path("id"));
                }));
            }
            var left = futures.get(0).get(60, TimeUnit.SECONDS); var right = futures.get(1).get(60, TimeUnit.SECONDS);
            assertEquals(Set.of(202, 409), Set.of(left.status(), right.status()));
            assertTrue((left.status() == 409 ? left.id() : right.id()) == null, "the conflict has no instance id");
            assertEquals(1, engine.starts());
            assertEquals(1L, count(dataSource, "select count(*) from task.instance where tenant = ? and idempotency_key = ?", tenant, key));
        } finally { pool.shutdownNow(); }
    }

    private record Outcome(int status, String id) {
    }
}
