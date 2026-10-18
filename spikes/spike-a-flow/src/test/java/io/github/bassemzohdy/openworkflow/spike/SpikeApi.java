package io.github.bassemzohdy.openworkflow.spike;

import static io.restassured.RestAssured.given;

import io.restassured.response.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Test helpers for the spike's own HTTP API and probe table. Shared by in-process and process tests. */
final class SpikeApi {

    static final String NS = "spike";

    /**
     * Process tests run outside Quarkus, so RestAssured needs an explicit target. In-process tests use the same
     * port because quarkus.http.test-port is pinned to it in application.properties.
     */
    static {
        io.restassured.RestAssured.baseURI = "http://localhost";
        io.restassured.RestAssured.port = 18094;
    }

    private SpikeApi() {
    }

    static String start(String name, String version, Map<String, Object> input) {
        Response r = given().contentType("application/json")
                .body(Map.of("namespace", NS, "name", name, "version", version, "input", input))
                .post("/spike/instances");
        r.then().statusCode(202);
        return r.path("id");
    }

    /** Returns the status, or null when the instance is neither active nor persisted. */
    static String status(String id, String name, String version) {
        Response r = given().queryParam("namespace", NS).queryParam("name", name).queryParam("version", version)
                .get("/spike/instances/{id}", id);
        return r.statusCode() == 200 ? r.path("status") : null;
    }

    static String source(String id, String name, String version) {
        Response r = given().queryParam("namespace", NS).queryParam("name", name).queryParam("version", version)
                .get("/spike/instances/{id}", id);
        return r.statusCode() == 200 ? r.path("source") : null;
    }

    static int statusCode(String id, String name, String version) {
        return given().queryParam("namespace", NS).queryParam("name", name).queryParam("version", version)
                .get("/spike/instances/{id}", id).statusCode();
    }

    static int probeCount(String tag, String instanceId) {
        Response r = given().queryParam("instanceId", instanceId).get("/spike/probe/{tag}", tag);
        r.then().statusCode(200);
        return r.path("count");
    }

    static List<String> observed(String id) {
        return given().get("/spike/observed/{id}", id).then().statusCode(200).extract().response().jsonPath()
                .getList("$");
    }

    static void emit(String type, String data) {
        given().contentType("application/json").body(Map.of("type", type, "data", data))
                .post("/spike/events").then().statusCode(202);
    }

    static void resume(String id, String name, String version) {
        given().queryParam("namespace", NS).queryParam("name", name).queryParam("version", version)
                .post("/spike/instances/{id}/resume", id).then().statusCode(202);
    }

    static void cancel(String id, String name, String version) {
        given().queryParam("namespace", NS).queryParam("name", name).queryParam("version", version)
                .post("/spike/instances/{id}/cancel", id).then().statusCode(200);
    }

    /** Polls until the condition holds, returning the elapsed milliseconds. Fails with the message after the timeout. */
    static long awaitTrue(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeout.toMillis()) {
            if (condition.getAsBoolean()) {
                return System.currentTimeMillis() - start;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out after " + timeout + " waiting for: " + what);
    }
}
