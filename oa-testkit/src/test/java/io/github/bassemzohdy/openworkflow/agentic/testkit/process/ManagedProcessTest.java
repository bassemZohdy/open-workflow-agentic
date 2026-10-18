package io.github.bassemzohdy.openworkflow.agentic.testkit.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedProcessTest {

    @TempDir
    Path tmp;

    private ManagedProcess fixture() throws Exception {
        return ManagedProcess.mainClass(System.getProperty("java.class.path"), FixtureHealthMain.class.getName())
                .logFile(tmp.resolve("app.log"))
                .readinessPath("/q/health/ready")
                .build();
    }

    @Test
    void startsAnswersKillsAndRestartsOnTheSamePort() throws Exception {
        try (ManagedProcess app = fixture()) {
            app.start();
            assertTrue(app.awaitReady(Duration.ofSeconds(60)), "not ready; log: " + app.logFile());
            assertEquals(200, app.httpStatus("/q/health/ready"));
            long firstPid = app.pid();

            app.kill();
            assertFalse(app.isAlive());
            assertEquals(-1, app.httpStatus("/q/health/ready"), "nothing should answer after kill -9");

            app.restart();
            assertTrue(app.awaitReady(Duration.ofSeconds(60)), "not ready after restart; log: " + app.logFile());
            assertNotEquals(firstPid, app.pid());
            assertEquals(200, app.httpStatus("/q/health/ready"));
        }
    }

    @Test
    void refusesToStartTwice() throws Exception {
        try (ManagedProcess app = fixture()) {
            app.start();
            assertTrue(app.awaitReady(Duration.ofSeconds(60)));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, app::start);
        }
    }

    @Test
    void reportsNotReadyWhenTheProcessExitsFirst() throws Exception {
        ManagedProcess broken = ManagedProcess.mainClass(System.getProperty("java.class.path"), "no.such.Main")
                .logFile(tmp.resolve("broken.log")).httpPort(ManagedProcess.freePort()).build();
        try (broken) {
            broken.start();
            assertFalse(broken.awaitReady(Duration.ofSeconds(60)));
        }
    }

    @Test
    void usesTheConfiguredPortAndPropertiesOnTheCommandLine() throws Exception {
        ManagedProcess app = ManagedProcess.mainClass(System.getProperty("java.class.path"), FixtureHealthMain.class.getName())
                .httpPort(4321).property("x.y", "z").logFile(tmp.resolve("props.log")).build();
        assertEquals(4321, app.port());
        assertEquals("http://127.0.0.1:4321", app.baseUrl());
    }
}
