package io.github.bassemzohdy.openworkflow.agentic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.testkit.process.ManagedProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Runs the packaged application (target/quarkus-app/quarkus-run.jar) as an operating system process, kills it with
 * SIGKILL, and starts it again against the same Postgres. Runs under failsafe, after the package phase.
 * No container runtime is needed: the database is the local Postgres on localhost:5432.
 */
class ProcessRestartIT {

    @Test
    void aKilledApplicationStartsAgainAndAnswersHealth() throws Exception {
        Path jar = Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "run `mvn verify` so the package phase builds " + jar);
        Path log = Path.of("target/process-restart-it.log").toAbsolutePath();

        try (ManagedProcess app = ManagedProcess.jar(jar).logFile(log).build()) {
            app.start();
            assertTrue(app.awaitReady(Duration.ofSeconds(180)), "not ready at first start; log: " + log);
            long firstPid = app.pid();
            assertEquals(200, app.httpStatus("/q/health/ready"));

            app.kill();
            assertEquals(-1, app.httpStatus("/q/health"), "nothing may answer after kill -9");

            app.restart();
            assertTrue(app.awaitReady(Duration.ofSeconds(180)), "not ready after restart; log: " + log);
            assertNotEquals(firstPid, app.pid(), "the restarted application must be a new process");
            assertEquals(200, app.httpStatus("/q/health"), "health must answer again after restart");
        }
    }
}
