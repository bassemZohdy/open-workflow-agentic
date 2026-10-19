package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.testkit.process.ManagedProcess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The packaged application runs in the production profile unless told otherwise. In that profile the development
 * header seam is refused even when {@code agentic.dev.trust-caller-headers=true} is set (WP-102, deliverable a):
 * the application does not start. Without the flag it starts and ignores the headers.
 *
 * <p>Run by failsafe after {@code package}. Uses ports 18493 and 18494.
 */
class HeaderSeamProductionIT {

    private static final Path JAR = Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath();

    @Test
    void theProductionProfileRefusesToStartWithTheSeamFlagSet() throws Exception {
        Path log = Path.of("target/process-logs/seam-prod-refused.log").toAbsolutePath();
        Files.createDirectories(log.getParent());
        Files.deleteIfExists(log);
        try (ManagedProcess app = ManagedProcess.jar(JAR).httpPort(18493).logFile(log)
                .property("agentic.dev.trust-caller-headers", "true")
                .property("agentic.reconciler.enabled", "false")
                .build()) {
            app.start();
            assertFalse(app.awaitReady(Duration.ofSeconds(150)), "the application must not become ready");
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (app.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertFalse(app.isAlive(), "the application must exit");
        }
        String text = Files.readString(log);
        assertTrue(text.contains("agentic.dev.trust-caller-headers=true is refused in profile(s) [prod]"),
                "the log must say why; log: " + log);
    }

    @Test
    void theProductionProfileWithoutTheFlagStartsAndIgnoresCallerHeaders() throws Exception {
        Path log = Path.of("target/process-logs/seam-prod-default.log").toAbsolutePath();
        Files.createDirectories(log.getParent());
        Files.deleteIfExists(log);
        try (ManagedProcess app = ManagedProcess.jar(JAR).httpPort(18494).logFile(log)
                .property("agentic.reconciler.enabled", "false")
                .build()) {
            app.start();
            assertTrue(app.awaitReady(Duration.ofSeconds(150)), "not ready; log: " + log);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(app.baseUrl() + "/operations/instances/01ARZ3NDEKTSV4RRFFQ69G5FAV"))
                            .header("X-Tenant", "t1").header("X-Subject", "ops").header("X-Scopes", "oa.recover")
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), response.body());
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(""));
        }
    }
}
