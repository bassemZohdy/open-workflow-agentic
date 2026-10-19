package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.testkit.process.ManagedProcess;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Process-level acceptance of the run lifecycle (WP-105c, scope item 6). The application runs as child JVMs on the
 * database named by {@code DB_URL}; each run is killed with SIGKILL ({@link ManagedProcess#kill()}), and the next run
 * starts on the same database.
 *
 * <p>Scenario A: a kill during the effect call. Without the reconciler the restored instance stays idle; with it the
 * instance resumes, the effect runs again with a higher resume epoch, and the instance completes when the event
 * arrives. Scenario B: a kill between the registry insert and the engine start (a dev fault point); the reconciler
 * starts the row that has no engine id. Both end with one registry row per request key.
 *
 * <p>Run by failsafe after {@code package}. The jar is {@code target/quarkus-app/quarkus-run.jar}.
 */
class LifecycleRecoveryIT {

    private static final Path JAR = Path.of("target/quarkus-app/quarkus-run.jar");
    private static final Path LOGS = Path.of("target/process-logs");
    private static final String DB_URL = System.getenv().getOrDefault("DB_URL",
            "jdbc:postgresql://localhost:5432/agentic");
    private static final String DEFINITION = "agentic/lifecycle-probe";
    private static final String EVENT = "org.openworkflow.agentic.lifecycle.probe";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final java.util.List<ManagedProcess> started = new java.util.ArrayList<>();

    @AfterEach
    void stopEverything() throws InterruptedException {
        for (ManagedProcess process : started) {
            process.kill();
        }
        started.clear();
    }

    /** Starts a child JVM on a fixed port with the test switches plus the given overrides. */
    private ManagedProcess app(String label, int port, boolean reconciler, Map<String, String> extra)
            throws IOException, InterruptedException {
        Files.createDirectories(LOGS);
        ManagedProcess.Builder builder = ManagedProcess.jar(JAR)
                .httpPort(port)
                .logFile(LOGS.resolve(label + ".log"))
                .property("agentic.dev.trust-caller-headers", "true")
                .property("agentic.dev.lifecycle-probes", "true")
                .property("agentic.reconciler.enabled", String.valueOf(reconciler))
                .property("agentic.reconciler.interval", "PT2S")
                .property("agentic.reconciler.start-stale-after", "PT2S")
                .property("agentic.reconciler.start-backoff", "PT1S")
                .property("agentic.reconciler.cancel-reissue-after", "PT2S");
        extra.forEach(builder::property);
        ManagedProcess process = builder.build();
        process.start();
        started.add(process);
        awaitReady(process, label, reconciler);
        return process;
    }

    /** Ready = Flow warmed up (its log line), the health endpoint answers, and the reconciler's pass ran if enabled. */
    private void awaitReady(ManagedProcess process, String label, boolean reconciler) throws InterruptedException {
        Path log = process.logFile();
        await(label + " ready", Duration.ofSeconds(150), () -> process.isAlive()
                && read(log).contains("Workflow definitions warmup complete")
                && (!reconciler || read(log).contains("lifecycle reconciler: startup pass complete"))
                && process.httpStatus("/q/health/ready") == 200);
    }

    private static String read(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("timed out waiting for " + what);
    }

    private static JsonNode post(ManagedProcess process, String path, String tenant, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(process.baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("X-Tenant", tenant)
                .header("X-Subject", "alice")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 202 || response.statusCode() == 200,
                "POST " + path + " answered " + response.statusCode() + ": " + response.body());
        return JSON.readTree(response.body());
    }

    private static JsonNode get(ManagedProcess process, String id, String tenant) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(process.baseUrl() + "/instances/" + id))
                .timeout(Duration.ofSeconds(10))
                .header("X-Tenant", tenant)
                .header("X-Subject", "alice").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static String status(ManagedProcess process, String id, String tenant) throws Exception {
        return get(process, id, tenant).get("status").asText();
    }

    private static String startInstance(ManagedProcess process, String tenant, String key, String mode)
            throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("effectUrl", process.baseUrl() + "/dev/lifecycle/effects");
        input.put("mode", mode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionId", DEFINITION);
        body.put("definitionVersion", "0.1.0");
        body.put("input", input);
        body.put("idempotencyKey", key);
        return post(process, "/instances", tenant, JSON.writeValueAsString(body)).get("id").asText();
    }

    /** Publishes the decision event until the instance completes: the listen may not be registered yet (WP-003, Q02). */
    private static void decideUntilCompleted(ManagedProcess process, String id, String tenant) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if ("COMPLETED".equals(status(process, id, tenant))) {
                return;
            }
            post(process, "/dev/lifecycle/events", tenant, "{\"type\":\"" + EVENT + "\"}");
            Thread.sleep(500);
        }
        fail("instance " + id + " did not complete after the event; status " + status(process, id, tenant));
    }

    private static long count(String sql, Object... params) throws Exception {
        try (Connection connection = DriverManager.getConnection(DB_URL, "postgres", "postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String registryId(String tenant, String key) throws Exception {
        try (Connection connection = DriverManager.getConnection(DB_URL, "postgres", "postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select id from task.instance where tenant = ? and idempotency_key = ?")) {
            statement.setString(1, tenant);
            statement.setString(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String newTenant() {
        return "it-" + UUID.randomUUID();
    }

    @Test
    void aKillDuringTheEffectResumesWithAnHigherEpochOnlyWhenTheReconcilerRuns() throws Exception {
        String tenant = newTenant();
        String key = "a-" + UUID.randomUUID();
        int port = ManagedProcess.freePort();

        ManagedProcess first = app("a-run1", port, true, Map.of());
        String id = startInstance(first, tenant, key, "hold");
        String engineId = await(first, id, tenant, "RUNNING");
        await("effect in flight", Duration.ofSeconds(60), () -> read(first.logFile())
                .contains("probe-effect received instance=" + engineId + " epoch=0 mode=hold"));
        first.kill();

        // Without the reconciler, the restored instance is registered as active but never started (WP-003, Q02).
        ManagedProcess idle = app("a-run2-no-reconciler", port, false, Map.of());
        Thread.sleep(6_000);
        assertEquals("RUNNING", status(idle, id, tenant), "without the reconciler the instance must stay idle");
        assertFalse(read(idle.logFile()).contains("epoch=1"), "nothing may run again without the reconciler");
        idle.kill();

        ManagedProcess resumed = app("a-run3-reconciler", port, true, Map.of());
        await("effect runs again with epoch 1", Duration.ofSeconds(60), () -> read(resumed.logFile())
                .contains("probe-effect received instance=" + engineId + " epoch=1 mode=hold"));
        await(resumed, id, tenant, "WAITING");
        decideUntilCompleted(resumed, id, tenant);

        assertEquals("COMPLETED", status(resumed, id, tenant));
        assertEquals(1L, count("select count(*) from task.instance where tenant = ? and idempotency_key = ?",
                tenant, key), "one registry row for the key");
        assertEquals(1L, count("select count(*) from task.repair_event where kind = 'resume' and subject = ?",
                registryId(tenant, key)), "one resume repair");
        assertNotNull(engineId);
    }

    @Test
    void aKillBetweenTheRegistryRowAndTheEngineStartIsRepairedByTheReconciler() throws Exception {
        String tenant = newTenant();
        String key = "b-" + UUID.randomUUID();
        int port = ManagedProcess.freePort();

        ManagedProcess crashing = app("b-run1-crash", port, true,
                Map.of("agentic.dev.fault.crash-at", "after-insert"));
        try {
            startInstance(crashing, tenant, key, "ok");
            fail("the process was expected to halt before it answered");
        } catch (IOException expectedConnectionLoss) {
            // The process halted with the request in flight: the client sees the connection close.
        }
        await("the crashed process to exit", Duration.ofSeconds(30), () -> !crashing.isAlive());
        assertTrue(read(crashing.logFile()).contains("dev fault point after-insert reached"),
                "the process must halt at the fault point, not fail elsewhere");
        assertEquals(1L, count("select count(*) from task.instance where tenant = ? and idempotency_key = ?"
                + " and status = 'STARTING' and engine_instance_id is null", tenant, key),
                "the registry row exists before any engine work");

        ManagedProcess repaired = app("b-run2-reconciler", port, true, Map.of());
        String id = registryId(tenant, key);
        assertNotNull(id);
        await(repaired, id, tenant, "WAITING");
        decideUntilCompleted(repaired, id, tenant);

        assertEquals(1L, count("select count(*) from task.instance where tenant = ? and idempotency_key = ?",
                tenant, key), "still one registry row for the key");
        assertTrue(count("select count(*) from task.repair_event where kind = 'start' and subject = ?"
                + " and outcome = 'started'", id) >= 1, "the reconciler's start is audited");
    }

    /** Waits until the registry shows one of the statuses, using the instance's own process. */
    private static String await(ManagedProcess process, String id, String tenant, String expected)
            throws Exception {
        await("status " + expected, Duration.ofSeconds(60), () -> {
            try {
                return expected.equals(status(process, id, tenant));
            } catch (Exception e) {
                return false;
            }
        });
        return get(process, id, tenant).path("engineInstanceId").asText();
    }
}
