package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.testkit.process.ManagedProcess;
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
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The outbox survives a SIGKILL (WP-105b). A process starts instances with its relay effectively off, so every change
 * is committed and unpublished. It is killed with SIGKILL. A second process on the same port runs the relay, and the
 * view must catch up: every instance projected once, with the registry's status and version, and nothing lost.
 *
 * <p>Runs under failsafe, after the package phase, against the database in DB_URL (default: the local agentic database).
 */
class OutboxKillIT {

    private static final String DB_URL = System.getenv().getOrDefault("DB_URL",
            "jdbc:postgresql://localhost:5432/agentic");
    private static final int INSTANCES = 5;

    @Test
    void aKilledProcessLosesNoOutboxRowAndTheViewCatchesUp() throws Exception {
        Path jar = Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "run `mvn verify` so the package phase builds " + jar);
        Path log = Path.of("target/outbox-kill-it.log").toAbsolutePath();
        String tenant = "kill-" + UUID.randomUUID();
        HttpClient http = HttpClient.newHttpClient();

        int port;
        try (ManagedProcess first = ManagedProcess.jar(jar).logFile(log)
                .property("agentic.dev.trust-caller-headers", "true")
                // The packaged jar runs in the production profile, which refuses the header seam (WP-102).
                .property("quarkus.profile", "itest")
                .property("agentic.dev.header-seam-profiles", "itest")
                .property("agentic.dev.lifecycle-probes", "true")
                .property("agentic.outbox.relay.interval", "1h")
                .build()) {
            first.start();
            assertTrue(first.awaitReady(Duration.ofSeconds(180)), "not ready at first start; log: " + log);
            port = first.port();
            for (int i = 0; i < INSTANCES; i++) {
                assertEquals(202, start(http, port, tenant), "the start is accepted");
            }
            // Committed, but the relay has not run: every start has unpublished rows. A start writes three history
            // rows: STARTING (created), the engine id recorded, and RUNNING (InstanceService).
            assertEquals(3L * INSTANCES, count("select count(*) from task.outbox o join task.instance i"
                    + " on i.id = o.partition_key where i.tenant = ? and o.published_at is null", tenant),
                    "three history rows per start, none published yet");
            assertEquals(0L, count("select count(*) from task.task_view where tenant = ?", tenant));
            first.kill();
            assertEquals(-1, first.httpStatus("/q/health"), "nothing may answer after kill -9");
        }

        try (ManagedProcess second = ManagedProcess.jar(jar).logFile(log).httpPort(port)
                .property("agentic.dev.trust-caller-headers", "true")
                // The packaged jar runs in the production profile, which refuses the header seam (WP-102).
                .property("quarkus.profile", "itest")
                .property("agentic.dev.header-seam-profiles", "itest")
                .property("agentic.reconciler.enabled", "false")
                .property("agentic.outbox.relay.interval", "200ms")
                .build()) {
            second.start();
            assertTrue(second.awaitReady(Duration.ofSeconds(180)), "not ready after restart; log: " + log);
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (count("select count(*) from task.outbox o join task.instance i on i.id = o.partition_key"
                    + " where i.tenant = ? and o.published_at is null", tenant) > 0) {
                assertTrue(System.nanoTime() < deadline, "the relay did not catch up; log: " + log);
                Thread.sleep(200);
            }
        }

        assertEquals((long) INSTANCES, count("select count(*) from task.task_view where tenant = ?", tenant),
                "every instance is in the view once");
        assertEquals((long) INSTANCES, count("""
                select count(*) from task.task_view v join task.instance i on i.id = v.instance_id
                 where v.tenant = ? and v.instance_status = i.status and v.instance_version = i.version
                   and v.last_outbox_id = (select max(o.id) from task.outbox o where o.partition_key = v.instance_id)
                """, tenant), "each view row matches the registry and its last outbox row");
        assertEquals(0L, count("select count(*) from task.outbox o join task.instance i"
                + " on i.id = o.partition_key where i.tenant = ? and o.failed_at is not null", tenant));
    }

    /**
     * Starts the lifecycle probe with a held effect call, so the instance stays RUNNING. The earlier definition
     * (research-and-approve) calls a server that does not exist in the child process, faults within milliseconds,
     * and the engine observer's FAULTED row raced the count of unpublished rows.
     */
    private static int start(HttpClient http, int port, String tenant) throws Exception {
        String body = """
                {"definitionId":"agentic/lifecycle-probe","definitionVersion":"0.1.0",
                 "input":{"effectUrl":"http://127.0.0.1:%d/dev/lifecycle/effects","mode":"hold"},"idempotencyKey":"k-%s"}
                """.formatted(port, UUID.randomUUID());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/instances"))
                .header("Content-Type", "application/json")
                .header("X-Tenant", tenant)
                .header("X-Subject", "alice")
                .header("X-Scopes", "oa.operate")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private static long count(String sql, String... params) throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL,
                System.getenv().getOrDefault("DB_USER", "postgres"),
                System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
