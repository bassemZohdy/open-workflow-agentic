package io.github.bassemzohdy.openworkflow.spike;

import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.awaitTrue;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.emit;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.probeCount;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.resume;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.source;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.start;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.status;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.statusCode;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Process-level characterization: the packaged application is started as a child process, killed with
 * SIGKILL (kill -9) and started again on the same database. These tests answer the restart questions
 * (Q02, Q03, Q04, Q06, Q11) that an in-process test cannot answer.
 *
 * <p>Run with: {@code mvn -B package -DskipTests} then
 * {@code SPIKE_PROCESS_TESTS=true mvn -B test -Dtest=ProcessRestartTest}.
 * They reset the spike_a database schema first, so they must not run against any other database.
 */
@EnabledIfEnvironmentVariable(named = "SPIKE_PROCESS_TESTS", matches = "true")
class ProcessRestartTest {

    private static final Path JAR = Path.of("target/quarkus-app/quarkus-run.jar");
    private static final Path LOGS = Path.of("target/process-logs");
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/spike_a";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Starts the packaged app with the given extra environment. Output goes to a per-run log file. */
    /** Labels used at readiness call sites; the actual log is found from the Process (see LOG_OF). */
    private static final String LABEL = "app";
    private static final String LABEL1 = "run1";
    private static final String LABEL2 = "run2";

    /** Log file of each started process, so readiness is judged against that process's own output. */
    private static final Map<Process, Path> LOG_OF = new java.util.concurrent.ConcurrentHashMap<>();

    private static Process startApp(String label, Map<String, String> env) throws IOException {
        if (!Files.exists(JAR)) {
            throw new IllegalStateException("missing " + JAR + "; run mvn -B package -DskipTests first");
        }
        Files.createDirectories(LOGS);
        Path log = LOGS.resolve(label + ".log");
        Files.deleteIfExists(log);
        ProcessBuilder pb = new ProcessBuilder("java", "-Dquarkus.http.port=18094", "-jar", JAR.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        pb.environment().putAll(env);
        Process p = pb.start();
        LOG_OF.put(p, log);
        STARTED.add(p);
        return p;
    }

    private static Process startApp(String label) throws IOException {
        return startApp(label, Map.of());
    }

    /**
     * Waits until the process we started answers on the port. A 200 from a different process holding the port
     * must not count, so the process must still be alive when it answers, and the answer must come after its log
     * shows the listener is up.
     */
    private static void waitReady(Process app, String ignoredLabel) throws Exception {
        // ignoredLabel is kept for readability at the call sites; the log is found from the Process itself.
        Path log = LOG_OF.get(app);
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(120).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!app.isAlive()) {
                throw new AssertionError("application exited during startup, exit code " + app.exitValue()
                        + "; see " + log);
            }
            // Ready only after Flow has finished warming definitions. The warm-up runs on a thread that can start
            // after the listener, and a restore started before it completes can be dropped (see findings).
            boolean listening = Files.exists(log) && Files.readString(log).contains("Listening on:")
                    && Files.readString(log).contains("Workflow definitions warmup complete");
            if (listening) {
                try {
                    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:18094/spike/definitions"))
                            .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 200 && app.isAlive()) {
                        return;
                    }
                } catch (IOException notYet) {
                    // listener logged but not yet accepting; retry
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("application not ready within 120 s; see " + log);
    }

    /** kill -9 (SIGKILL). Returns the exit code; 137 means the process died from signal 9. */
    private static int kill9(Process app) throws Exception {
        Process kill = new ProcessBuilder("kill", "-9", Long.toString(app.pid())).start();
        kill.waitFor();
        assertTrue(app.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "process did not die after kill -9");
        return app.exitValue();
    }

    /** Every application started by a test; all are stopped and awaited after each test. */
    private static final java.util.List<Process> STARTED = new java.util.concurrent.CopyOnWriteArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void stopEverything() throws Exception {
        for (Process p : STARTED) {
            awaitExit(p);
        }
        STARTED.clear();
    }

    /** Waits until the process is gone, so the next application never shares a database with a dying JVM. */
    private static void awaitExit(Process app) throws Exception {
        if (!app.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            app.destroyForcibly();
            app.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void resetDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL, "postgres", "postgres");
                var st = c.createStatement()) {
            st.execute("drop schema if exists public cascade");
            st.execute("create schema public");
        }
    }

    private static List<String> tables() throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL, "postgres", "postgres");
                var rs = c.createStatement().executeQuery(
                        "select table_name from information_schema.tables where table_schema='public' order by 1")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /**
     * Q02, default behavior: after kill -9 the waiting instance is found by the restore scan and registered as active,
     * but it is never started. It stays PENDING and does not resume on the next event. Kept as a @Disabled
     * capability gap; the observed failure is in docs/spikes/WP-003-quarkus-flow.md.
     */
    @Test
    @org.junit.jupiter.api.Disabled("Q02 gap: restored instances are registered active but not started (PENDING). "
            + "Restore scan skips start() because WorkflowMutableInstance registers itself in the constructor.")
    void Q02_restoredWaitingInstanceResumesWithoutHelp() throws Exception {
        resetDatabase();
        Process app1 = startApp("q02-run1");
        Process app2 = null;
        try {
            waitReady(app1, LABEL1);
            String id = start("spike-listen", "1.0.0", Map.of("tag", "q02"));
            awaitTrue("waiting before kill", Duration.ofSeconds(30),
                    () -> probeCount("before", id) == 1 && "WAITING".equals(status(id, "spike-listen", "1.0.0")));
            assertEquals(137, kill9(app1), "expected death by SIGKILL (137)");
            app2 = startApp("q02-run2");
            waitReady(app2, LABEL2);
            awaitTrue("restored instance waiting again", Duration.ofSeconds(30),
                    () -> "WAITING".equals(status(id, "spike-listen", "1.0.0")));
        } finally {
            if (app2 != null) {
                app2.destroy();
            }
            app1.destroyForcibly();
        }
    }

    /**
     * Q02, with the explicit resume call: after kill -9 and restart, the instance is started through the API,
     * waits again and resumes on the next event. The completed task before the listen is not repeated.
     */
    @Test
    void Q02_listenResumesAfterKill9WhenStartedThroughApi() throws Exception {
        resetDatabase();
        Process app1 = startApp("q02-run1");
        Process app2 = null;
        try {
            waitReady(app1, "q02-run1");
            String tag = "q02";
            String id = start("spike-listen", "1.0.0", Map.of("tag", tag));
            awaitTrue("waiting before kill", Duration.ofSeconds(30),
                    () -> probeCount("before", id) == 1 && "WAITING".equals(status(id, "spike-listen", "1.0.0")));
            assertEquals(137, kill9(app1), "expected death by SIGKILL (137)");

            app2 = startApp("q02-run2");
            waitReady(app2, LABEL2);
            // Restored but not started (see Q02_restoredWaitingInstanceResumesWithoutHelp). Start it through the API.
            // The status the query reports is the in-memory PENDING state, not the persisted (null) status.
            // The restore scan runs after the listener starts, so wait for the instance to be registered in memory.
            awaitTrue("restore registered the instance in memory", Duration.ofSeconds(30),
                    () -> "active".equals(source(id, "spike-listen", "1.0.0")));
            resume(id, "spike-listen", "1.0.0");
            awaitTrue("restored instance waiting again", Duration.ofSeconds(30),
                    () -> "WAITING".equals(status(id, "spike-listen", "1.0.0")));
            assertEquals("active", source(id, "spike-listen", "1.0.0"), "restored instance is in memory again");
            assertEquals(1, probeCount("before", id), "the completed task before the listen must not run again");

            emit("spike.go", "{}");
            awaitTrue("resumed after event", Duration.ofSeconds(30), () -> probeCount("after", id) == 1);
            awaitTrue("completed and left the engine", Duration.ofSeconds(30),
                    () -> statusCode(id, "spike-listen", "1.0.0") == 404);
        } finally {
            if (app2 != null) {
                app2.destroy();
            }
            app1.destroyForcibly();
        }
    }

    /** Q11: kill -9 while a task is in flight. The task that had not completed is executed again after restart. */
    @Test
    void Q11_killDuringInFlightTaskRerunsThatTask() throws Exception {
        resetDatabase();
        Process app1 = startApp("q11-run1");
        Process app2 = null;
        try {
            waitReady(app1, LABEL1);
            String id = start("spike-effect", "1.0.0", Map.of("tag", "q11"));
            awaitTrue("external effect started", Duration.ofSeconds(30), () -> probeCount("effect", id) == 1);
            assertEquals(137, kill9(app1));

            app2 = startApp("q11-run2");
            waitReady(app2, LABEL2);
            // Same restore gap as Q02: the instance is registered but not started, so nothing re-runs the task.
            awaitTrue("restored in memory", Duration.ofSeconds(30), () -> "active".equals(source(id, "spike-effect", "1.0.0")));
            resume(id, "spike-effect", "1.0.0");
            awaitTrue("effect executed a second time after restart", Duration.ofSeconds(40),
                    () -> probeCount("effect", id) == 2);
            awaitTrue("workflow continued after the effect", Duration.ofSeconds(40),
                    () -> probeCount("effect-after", id) == 1);
            System.out.println("Q11 effect executions for one logical task: " + probeCount("effect", id));
        } finally {
            if (app2 != null) {
                app2.destroy();
            }
            app1.destroyForcibly();
        }
    }

    /**
     * Q11, default restart: no explicit resume after kill -9. Observed: the in-flight task is not re-run; the instance
     * stays registered but not started. Kept @Disabled as a capability gap (see findings).
     */
    @Test
    @org.junit.jupiter.api.Disabled("Q11 gap: after kill -9 the in-flight task is not re-run on restart unless the instance is started.")
    void Q11_inFlightTaskRerunsWithoutHelp() throws Exception {
        resetDatabase();
        Process app1 = startApp("q11d-run1");
        Process app2 = null;
        try {
            waitReady(app1, LABEL1);
            String id = start("spike-effect", "1.0.0", Map.of("tag", "q11d"));
            awaitTrue("external effect started", Duration.ofSeconds(30), () -> probeCount("effect", id) == 1);
            assertEquals(137, kill9(app1));
            app2 = startApp("q11d-run2");
            waitReady(app2, LABEL2);
            awaitTrue("effect executed a second time after restart", Duration.ofSeconds(40),
                    () -> probeCount("effect", id) == 2);
        } finally {
            if (app2 != null) {
                app2.destroy();
            }
            app1.destroyForcibly();
        }
    }

    /** Q03: a listen deadline is not stored as an absolute time; after restart the full duration is counted again. */
    @Test
    void Q03_listenDeadlineIsRearmedAfterRestart() throws Exception {
        resetDatabase();
        Process app1 = startApp("q03-run1");
        Process app2 = null;
        try {
            waitReady(app1, LABEL1);
            String id = start("spike-listen-timeout", "1.0.0", Map.of("tag", "q03"));
            awaitTrue("waiting with an 8 s deadline", Duration.ofSeconds(30), () -> probeCount("timeout-before", id) == 1);
            Thread.sleep(3000);
            assertEquals(137, kill9(app1));

            app2 = startApp("q03-run2");
            waitReady(app2, LABEL2);
            awaitTrue("restored in memory", Duration.ofSeconds(30), () -> "active".equals(source(id, "spike-listen-timeout", "1.0.0")));
            long resumedAt = System.currentTimeMillis();
            resume(id, "spike-listen-timeout", "1.0.0");
            long toGone = awaitTrue("timed-out instance left the engine", Duration.ofSeconds(40),
                    () -> statusCode(id, "spike-listen-timeout", "1.0.0") == 404);
            long fromResume = System.currentTimeMillis() - resumedAt;
            System.out.println("Q03 deadline after restart: left the engine " + toGone + " ms after resume (original deadline 8 s,"
                    + " 3 s elapsed before the kill)");
            // If the deadline were stored as an absolute time, it would already have passed and fire at once.
            // Firing after roughly the full 8 s from resume shows the deadline is re-armed from the restart.
            assertTrue(fromResume >= 6000, "deadline must be re-armed from resume, fired after " + fromResume + " ms");
            assertEquals(0, probeCount("timeout-after", id));
        } finally {
            if (app2 != null) {
                app2.destroy();
            }
            app1.destroyForcibly();
        }
    }

    /** Q04: two versions of one definition are both restored after kill -9 and each resumes on its own event. */
    @Test
    void Q04_twoVersionsRestoredAfterKill9() throws Exception {
        resetDatabase();
        Process app1 = startApp("q04-run1");
        Process app2 = null;
        try {
            waitReady(app1, LABEL1);
            String v1 = start("spike-listen", "1.0.0", Map.of("tag", "q04a"));
            String v2 = start("spike-listen", "2.0.0", Map.of("tag", "q04b"));
            awaitTrue("v1 waiting", Duration.ofSeconds(30), () -> "WAITING".equals(status(v1, "spike-listen", "1.0.0")));
            awaitTrue("v2 waiting", Duration.ofSeconds(30), () -> "WAITING".equals(status(v2, "spike-listen", "2.0.0")));
            assertEquals(137, kill9(app1));

            app2 = startApp("q04-run2");
            waitReady(app2, LABEL2);
            // Same restore gap as Q02: both versions are registered but not started; start each through the API.
            awaitTrue("v1 restored in memory", Duration.ofSeconds(30), () -> "active".equals(source(v1, "spike-listen", "1.0.0")));
            awaitTrue("v2 restored in memory", Duration.ofSeconds(30), () -> "active".equals(source(v2, "spike-listen", "2.0.0")));
            resume(v1, "spike-listen", "1.0.0");
            resume(v2, "spike-listen", "2.0.0");
            awaitTrue("v1 waiting", Duration.ofSeconds(30), () -> "WAITING".equals(status(v1, "spike-listen", "1.0.0")));
            awaitTrue("v2 waiting", Duration.ofSeconds(30), () -> "WAITING".equals(status(v2, "spike-listen", "2.0.0")));
            emit("spike.go.v2", "{}");
            awaitTrue("v2 resumed", Duration.ofSeconds(30), () -> probeCount("after-v2", v2) == 1);
            assertEquals(0, probeCount("after", v1), "v1 must not move when only v2's event arrives");
            assertEquals("WAITING", status(v1, "spike-listen", "1.0.0"));
        } finally {
            if (app2 != null) {
                app2.destroy();
            }
            app1.destroyForcibly();
        }
    }

    /**
     * Q08: with Quarkus Flow's default HTTP client settings (3 client retries, circuit breaker on), a failing POST is
     * retried inside the HTTP client. The OWS retry policy in the definition never fires (no TaskRetried event), so the
     * retry is invisible in the workflow trace, and the non-idempotent POST is still repeated.
     */
    @Test
    void Q08_defaultClientRetryRepeatsPostBelowOwsPolicy() throws Exception {
        resetDatabase();
        Process app = startApp("q08-default");
        try {
            waitReady(app, "q08-default");
            String id = start("spike-flaky-post", "1.0.0", Map.of("tag", "q08"));
            awaitTrue("post completed", Duration.ofSeconds(30), () -> probeCount("flaky-post-after", id) == 1);
            System.out.println("Q08 default client: server saw POST attempts = "
                    + given().get("/spike/flaky/p1/attempts").jsonPath().getInt("post"));
            List<String> events = SpikeApi.observed(id);
            System.out.println("Q08 default client: events = " + events);
            assertEquals(0, events.stream().filter(s -> s.startsWith("TaskRetried")).count(), events.toString());
            assertEquals(3, given().get("/spike/flaky/p1/attempts").jsonPath().getInt("post"));
        } finally {
            app.destroyForcibly();
        }
    }

    /**
     * Q05: with automatic restore switched off, a persisted instance is visible through the reader only after it is read,
     * and the first read registers an in-memory object with status PENDING (not WAITING).
     */
    @Test
    void Q05_persistedInstanceWithAutoRestoreOff() throws Exception {
        resetDatabase();
        Map<String, String> off = new HashMap<>();
        off.put("QUARKUS_FLOW_PERSISTENCE_AUTO_RESTORE", "false");
        Process app1 = startApp("q05-run1", off);
        Process app2 = null;
        try {
            waitReady(app1, LABEL1);
            String id = start("spike-listen", "1.0.0", Map.of("tag", "q05"));
            awaitTrue("waiting", Duration.ofSeconds(30), () -> "WAITING".equals(status(id, "spike-listen", "1.0.0")));
            assertEquals(137, kill9(app1));

            app2 = startApp("q05-run2", off);
            waitReady(app2, LABEL2);
            assertEquals("persisted", source(id, "spike-listen", "1.0.0"), "first read finds the persisted row");
            System.out.println("Q05 second read after persisted read: source="
                    + source(id, "spike-listen", "1.0.0") + " status=" + status(id, "spike-listen", "1.0.0"));
        } finally {
            if (app2 != null) {
                app2.destroy();
            }
            app1.destroyForcibly();
        }
    }

    /**
     * Q06: the Flow JPA tables come only from Hibernate entities; the module ships no Flyway script. With Hibernate
     * generation off, Flyway alone leaves the Flow tables missing and the application does not finish starting its
     * workflows (the readiness marker never appears). Observed, not assumed.
     */
    @Test
    void Q06_withoutHibernateGenerationFlowTablesAreMissing() throws Exception {
        resetDatabase();
        Map<String, String> none = new HashMap<>();
        none.put("SPIKE_HIBERNATE_GENERATION", "none");
        Process app = startApp("q06-none", none);
        try {
            // Wait for the failure marker rather than readiness: this run is expected to fail to warm up.
            awaitTrue("workflow warm-up failed on missing tables", Duration.ofSeconds(60), () -> {
                try {
                    return Files.readString(LOGS.resolve("q06-none.log")).contains("Failed to initialize and warm up workflows");
                } catch (IOException e) {
                    return false;
                }
            });
            List<String> tables = tables();
            System.out.println("Q06 public tables after Flyway only: " + tables);
            assertFalse(tables.contains("workflowinstanceentity"), tables.toString());
            assertTrue(tables.contains("flyway_schema_history"), tables.toString());
        } finally {
            app.destroyForcibly();
        }
    }
}
