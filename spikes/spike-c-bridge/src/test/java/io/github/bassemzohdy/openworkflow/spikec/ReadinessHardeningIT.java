package io.github.bassemzohdy.openworkflow.spikec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #9, requirement 1: a failed workflow recovery keeps the application not ready, and new instances are
 * refused. The failure is forced with {@code spikec.reconcile.fail-attempts} (test-only, zero by default). The retry
 * rule: the reconciliation runs {@code spikec.reconcile.max-attempts} times, {@code spikec.reconcile.retry-ms} apart.
 * Readiness is true only after an attempt succeeds. When every attempt fails it stays false, with no further retry.
 */
class ReadinessHardeningIT {

    private static final String DEADLINE = "2026-10-20T00:00:00Z";

    private static Map<String, Object> body(String correlationId) {
        return Map.of("tenant", "t1", "user", "u1", "correlationId", correlationId, "deadline", DEADLINE,
                "budget", 5, "topic", "readiness " + correlationId);
    }

    @BeforeEach
    void freshDatabase() throws Exception {
        Harness.stopAll();
        Harness.resetDatabase();
    }

    @AfterEach
    void stopProcesses() {
        Harness.stopAll();
    }

    /** Every recovery attempt fails: readiness stays 503, the start is refused with a problem, nothing is recorded. */
    @Test
    void forcedRecoveryErrorKeepsReadinessDownAndRejectsNewInstances() throws Exception {
        Harness.App app = Harness.start("forced-error", Map.of(
                "spikec.reconcile.fail-attempts", "100",
                "spikec.reconcile.max-attempts", "3",
                "spikec.reconcile.retry-ms", "300"));
        app.awaitLog("reconciliation", Duration.ofSeconds(120));
        // Long enough for every attempt, and for any later self-recovery, to have happened.
        Thread.sleep(8_000);

        assertEquals(503, Harness.status("/spikec/ready"), "readiness after a forced recovery error");

        Harness.Raw refused = Harness.postRaw("/spikec/instances/a", body("c-refused"));
        assertEquals(503, refused.status(), "new instance while not ready: " + refused.body());
        assertTrue(refused.contentType().contains("application/problem+json"),
                "refusal is a problem response: " + refused.contentType());
        assertEquals(0, Harness.count("select count(*) from spikec.bridge_instance"),
                "a refused start writes no registry row");

        Thread.sleep(3_000);
        assertEquals(503, Harness.status("/spikec/ready"), "readiness flipped later without a successful retry");
        app.stop();
    }

    /** One failed attempt, then a retry that succeeds: readiness opens, and only then starts are accepted. */
    @Test
    void readinessOpensOnlyAfterARetrySucceeds() throws Exception {
        Harness.App app = Harness.start("retry-ok", Map.of(
                "spikec.reconcile.fail-attempts", "1",
                "spikec.reconcile.max-attempts", "3",
                "spikec.reconcile.retry-ms", "300"));
        app.awaitLog("complete on attempt 2 of 3", Duration.ofSeconds(120));
        app.awaitReady();

        Harness.Raw accepted = Harness.postRaw("/spikec/instances/a", body("c-accepted"));
        assertEquals(202, accepted.status(), "start after recovery succeeded: " + accepted.body());
        app.stop();
    }
}
