package io.github.bassemzohdy.openworkflow.spike;

import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.awaitTrue;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.cancel;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.emit;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.observed;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.probeCount;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.source;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.start;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.status;
import static io.github.bassemzohdy.openworkflow.spike.SpikeApi.statusCode;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowModel;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Characterization of Quarkus Flow 1.2.0 behavior in-process. Each test pins down one capability question.
 * Test names carry the question number (Q01 to Q10). Process-level questions (restart, kill -9) are in ProcessRestartTest.
 */
@QuarkusTest
class QuarkusFlowCharacterizationTest {

    @Inject
    SpikeJavaFlow javaFlow;

    @Inject
    SpikeContextFlow contextFlow;

    @Inject
    DataSource dataSource;

    @Inject
    WorkflowApplication application;

    private static String tag() {
        return "t" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Q01: an instance is started by API with input and the caller gets its id back at once. */
    @Test
    void Q01_startByApiWithInputReturnsIdAndInstanceIsQueryable() throws Exception {
        String id1 = start("spike-listen", "1.0.0", Map.of("tag", tag()));
        String id2 = start("spike-listen", "1.0.0", Map.of("tag", tag()));
        assertFalse(id1.isBlank());
        assertNotEquals(id1, id2, "two starts must produce distinct instance ids");
        // Observed format is recorded in the findings; the assertion only pins that the id is usable.
        awaitTrue("first instance waiting", Duration.ofSeconds(15), () -> "WAITING".equals(status(id1, "spike-listen", "1.0.0")));
        assertEquals("active", source(id1, "spike-listen", "1.0.0"));
        cancel(id1, "spike-listen", "1.0.0");
        cancel(id2, "spike-listen", "1.0.0");
    }

    /** Q02: a listen task waits, an emit task (in another instance) publishes, the listener resumes. */
    @Test
    void Q02_listenResumesWhenAnotherInstanceEmitsTheEvent() throws Exception {
        String tag = tag();
        String waiting = start("spike-listen", "1.0.0", Map.of("tag", tag));
        awaitTrue("listener reached listen", Duration.ofSeconds(15),
                () -> probeCount("before", waiting) == 1 && "WAITING".equals(status(waiting, "spike-listen", "1.0.0")));
        start("spike-emit", "1.0.0", Map.of("from", tag));
        awaitTrue("listener resumed after emit", Duration.ofSeconds(15), () -> probeCount("after", waiting) == 1);
        awaitTrue("listener completed and left the engine", Duration.ofSeconds(15),
                () -> statusCode(waiting, "spike-listen", "1.0.0") == 404);
        assertTrue(observed(waiting).stream().anyMatch(s -> s.startsWith("WorkflowCompleted")), observed(waiting).toString());
    }

    /** Q02: an event published before the listen task is registered is not buffered; a later listener does not see it. */
    @Test
    void Q02_eventPublishedBeforeListenIsNotBuffered() throws Exception {
        String tag = tag();
        emit("spike.go", "{\"early\":\"" + tag + "\"}");
        Thread.sleep(500);
        String id = start("spike-listen", "1.0.0", Map.of("tag", tag));
        awaitTrue("listener waiting", Duration.ofSeconds(15), () -> "WAITING".equals(status(id, "spike-listen", "1.0.0")));
        Thread.sleep(2000);
        assertEquals("WAITING", status(id, "spike-listen", "1.0.0"), "early event must not complete the listener");
        assertEquals(0, probeCount("after", id));
        cancel(id, "spike-listen", "1.0.0");
    }

    /** Q03: a task-level deadline fails the task before the called endpoint answers. */
    @Test
    void Q03_taskTimeoutFailsTheInstanceBeforeTheCallEnds() throws Exception {
        String id = start("spike-task-timeout", "1.0.0", Map.of("tag", tag()));
        long total = awaitTrue("timed-out instance removed from the engine", Duration.ofSeconds(20),
                () -> statusCode(id, "spike-task-timeout", "1.0.0") == 404);
        System.out.println("Q03 task timeout: instance left the engine after " + total + " ms");
        assertTrue(total < 9000, "deadline of 2 s must fire well before the 10 s endpoint answers, took " + total + " ms");
        assertEquals(0, probeCount("task-timeout-after", id));
        List<String> events = observed(id);
        System.out.println("Q03 events for the timed-out instance: " + events);
        assertTrue(events.stream().anyMatch(s -> s.startsWith("WorkflowFailed") && s.contains("timeout")), events.toString());
    }

    /** Q04: two versions of one definition name run at the same time, each keeps its own instances. */
    @Test
    void Q04_twoVersionsRunAtTheSameTime() throws Exception {
        String v1 = start("spike-listen", "1.0.0", Map.of("tag", tag()));
        String v2 = start("spike-listen", "2.0.0", Map.of("tag", tag()));
        awaitTrue("v1 waiting", Duration.ofSeconds(15), () -> "WAITING".equals(status(v1, "spike-listen", "1.0.0")));
        awaitTrue("v2 waiting", Duration.ofSeconds(15), () -> "WAITING".equals(status(v2, "spike-listen", "2.0.0")));
        // Observed (not desired): an instance id looked up through the other version's definition is still found,
        // because the persisted row is keyed by (instance id, application id), not by definition version.
        // Our API therefore has to check the version of the row itself (see ADR 0019 and the findings).
        assertEquals(200, statusCode(v1, "spike-listen", "2.0.0"), "lookup by id is not version-scoped");
        assertEquals("persisted", source(v1, "spike-listen", "2.0.0"));
        assertTrue(given().get("/spike/definitions").asString().contains("spike:spike-listen:2.0.0"));
        emit("spike.go.v2", "{}");
        awaitTrue("v2 completed", Duration.ofSeconds(15), () -> probeCount("after-v2", v2) == 1);
        assertEquals(0, probeCount("after", v1));
        assertEquals("WAITING", status(v1, "spike-listen", "1.0.0"));
        cancel(v1, "spike-listen", "1.0.0");
    }

    /** Q05: a waiting instance is queryable and cancellable; a cancelled instance leaves no queryable state. */
    @Test
    void Q05_queryAndCancelActiveInstance() throws Exception {
        String id = start("spike-listen", "1.0.0", Map.of("tag", tag()));
        awaitTrue("waiting", Duration.ofSeconds(15), () -> "WAITING".equals(status(id, "spike-listen", "1.0.0")));
        cancel(id, "spike-listen", "1.0.0");
        // Observed: cancel() returns before the lifecycle and persistence listeners finish. The instance is removed
        // about 100 to 200 ms later, so the API must treat cancel as accepted and asynchronous (see ADR 0019).
        long gone = awaitTrue("cancelled instance left the engine", Duration.ofSeconds(5),
                () -> statusCode(id, "spike-listen", "1.0.0") == 404);
        System.out.println("Q05 cancelled instance left the engine after " + gone + " ms");
        assertTrue(observed(id).stream().anyMatch(s -> s.startsWith("WorkflowCancelled")), observed(id).toString());
        assertEquals(0, probeCount("after", id));
    }

    /** Q06: which tables exist after startup (Hibernate-generated Flow tables next to the Flyway-owned spike table). */
    @Test
    void Q06_flowTablesAndFlywayHistoryExistAfterStartup() throws Exception {
        List<String> tables = new java.util.ArrayList<>();
        try (var c = dataSource.getConnection();
                var rs = c.createStatement().executeQuery(
                        "select table_name from information_schema.tables where table_schema='public' order by 1")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        System.out.println("Q06 public tables: " + tables);
        assertTrue(tables.contains("spike_probe"), tables.toString());
        assertTrue(tables.contains("flyway_schema_history"), tables.toString());
        // Table names are the entity names, lower-cased, with no underscores (Hibernate default naming here).
        assertTrue(tables.contains("workflowinstanceentity"), tables.toString());
        assertTrue(tables.contains("taskinfoentity"), tables.toString());
    }

    /** Q08: the OWS try/retry policy re-runs a failing call until it succeeds within the attempt limit (client retry off). */
    @Test
    void Q08_owsRetryPolicyRetriesUntilSuccess() throws Exception {
        String id = start("spike-retry", "1.0.0", Map.of("tag", tag()));
        awaitTrue("retry path completed", Duration.ofSeconds(20), () -> probeCount("retry-after", id) == 1);
        long retried = observed(id).stream().filter(s -> s.startsWith("TaskRetried")).count();
        assertEquals(2, retried, observed(id).toString());
        assertEquals(3, given().get("/spike/flaky/r1/attempts").jsonPath().getInt("get"));
    }

    /**
     * Q08: a non-idempotent POST that fails after its effect is applied is repeated by the OWS retry policy.
     * The endpoint records one row per request before failing, so the row count is the number of effects.
     */
    @Test
    void Q08_owsRetryRepeatsNonIdempotentPost() throws Exception {
        String id = start("spike-flaky-post", "1.0.0", Map.of("tag", tag()));
        awaitTrue("post completed after retries", Duration.ofSeconds(20), () -> probeCount("flaky-post-after", id) == 1);
        assertEquals(3, given().get("/spike/flaky/p1/attempts").jsonPath().getInt("post"));
        assertEquals(3, given().get("/spike/probe/flaky-post").jsonPath().getInt("count"),
                "each retry re-applied the effect the endpoint records");
    }

    /** Q09: a Java DSL function task calls a CDI bean. */
    @Test
    void Q09_javaDslFunctionTaskCallsCdiBean() throws Exception {
        WorkflowDefinition def = javaFlow.definition();
        WorkflowModel out = def.instance("world").start().join();
        assertEquals("hello world", out.as(String.class).orElseThrow());
    }

    /**
     * Q09: a YAML definition that names a CDI bean as a function (call: Java with a bean name). Expected to fail:
     * the YAML path cannot bind a Java object. Kept @Disabled; the recorded failure is in the findings document.
     */
    @Test
    @org.junit.jupiter.api.Disabled("Q09 gap, observed: UnsupportedOperationException: Unrecognized function greetingService"
            + " (JavaCallFunctionBuilder accepts only a Java object, not a bean name)")
    void Q09_yamlCallByBeanNameIsNotSupported() throws Exception {
        String yaml = """
                document:
                  dsl: '1.0.0'
                  namespace: spike
                  name: spike-yaml-bean
                  version: '1.0.0'
                do:
                  - greet:
                      call: Java
                      with:
                        function: greetingService
                """;
        Workflow workflow = WorkflowReader.readWorkflowFromString(yaml, WorkflowFormat.YAML);
        WorkflowDefinition def = application.workflowDefinition(workflow);
        WorkflowModel out = def.instance("world").start().join();
        assertEquals("hello world", out.as(String.class).orElseThrow());
    }

    /**
     * Q10: MDC set by the caller before start() is NOT visible inside a function task. The workflow runs on an
     * executor thread, and only the OpenTelemetry context is propagated (FlowContextPropagator). Pinned as observed.
     */
    @Test
    void Q10_mdcSetByCallerIsNotVisibleInsideFunctionTask() throws Exception {
        MDC.put("spikeCorrelation", "corr-123");
        try {
            WorkflowModel out = contextFlow.definition().instance("x").start().join();
            String seen = out.as(String.class).orElseThrow();
            System.out.println("Q10 MDC inside function task: " + seen);
            assertEquals("null", seen, "caller MDC does not cross into the task thread");
        } finally {
            MDC.remove("spikeCorrelation");
        }
    }

    /** Q10: the HTTP hop carries the instance and task ids as X-Flow headers and no caller header. */
    @Test
    void Q10_httpTaskCarriesInstanceAndTaskHeadersOnly() throws Exception {
        String tag = tag();
        String id = start("spike-listen", "1.0.0", Map.of("tag", tag));
        awaitTrue("probe written", Duration.ofSeconds(15), () -> probeCount("before", id) == 1);
        Map<String, Object> row = given().get("/spike/probe/before?instanceId=" + id).jsonPath().getMap("rows[0]");
        assertEquals(id, row.get("instanceId"));
        assertTrue(String.valueOf(row.get("taskId")).contains("probeBefore"), String.valueOf(row.get("taskId")));
        assertTrue(String.valueOf(row.get("detail")).contains("X-Flow-Instance-Id"), String.valueOf(row.get("detail")));
        assertEquals(null, row.get("correlation"), "caller correlation header is not forwarded");
        cancel(id, "spike-listen", "1.0.0");
    }
}
