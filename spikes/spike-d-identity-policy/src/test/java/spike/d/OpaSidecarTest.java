package spike.d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPA as a sidecar: a real `opa run --server` process (binary from OPA_BIN, v1.13.2 verified by SHA-256
 * against the GitHub release) queried over HTTP. Skipped when OPA_BIN is not set.
 */
class OpaSidecarTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPA_BIN = System.getenv("OPA_BIN");

    private Process opa;
    private Path workDir;
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

    @BeforeAll
    static void requireBinary() {
        Assumptions.assumeTrue(OPA_BIN != null && Files.isExecutable(Path.of(OPA_BIN)),
                "set OPA_BIN to an opa executable to run the sidecar tests");
    }

    @BeforeEach
    void startSidecar() throws IOException, InterruptedException {
        workDir = Files.createTempDirectory("opa-spike");
        Path policy = workDir.resolve("tool_authz.rego");
        Files.writeString(policy, resource("/opa/tool_authz.rego"));
        port = freePort();
        opa = start(policy);
        awaitReady();
    }

    @AfterEach
    void stopSidecar() throws InterruptedException {
        if (opa != null && opa.isAlive()) {
            opa.destroyForcibly();
            opa.waitFor();
        }
    }

    private Process start(Path policy) throws IOException {
        return new ProcessBuilder(OPA_BIN, "run", "--server", "--addr", "127.0.0.1:" + port,
                "--log-level", "error", policy.toString())
                .redirectErrorStream(true)
                .redirectOutput(workDir.resolve("opa.log").toFile())
                .start();
    }

    private void awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/health")).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // not listening yet
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("opa did not become ready");
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    /** The PEP: a decision is allow only for a boolean true result from a 200 response. Everything else denies. */
    boolean allowed(Map<String, Object> input) {
        try {
            String body = JSON.writeValueAsString(Map.of("input", input));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/v1/data/tool/allow"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(500))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return false;
            }
            JsonNode result = JSON.readTree(r.body()).path("result");
            return result.isBoolean() && result.asBoolean();
        } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
        }
    }

    static Map<String, Object> input(String tool, String requiredScope, List<String> scopes, int budget,
                                     String risk, boolean approved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "call_tool");
        m.put("tool", tool);
        m.put("required_scope", requiredScope);
        m.put("scopes", scopes);
        m.put("budget_remaining", budget);
        m.put("risk", risk);
        m.put("human_approved", approved);
        return m;
    }

    @Test
    void allowsScopedBudgetedLowRiskCall() {
        assertTrue(allowed(input("search", "cases:read", List.of("cases:read"), 5, "low", false)));
    }

    @Test
    void defaultDenyWhenScopeMissing() {
        assertFalse(allowed(input("close_case", "cases:write", List.of("cases:read"), 5, "high", true)));
    }

    @Test
    void defaultDenyWhenBudgetExhausted() {
        assertFalse(allowed(input("search", "cases:read", List.of("cases:read"), 0, "low", false)));
    }

    @Test
    void highRiskNeedsApproval() {
        assertFalse(allowed(input("close_case", "cases:write", List.of("cases:write"), 5, "high", false)));
        assertTrue(allowed(input("close_case", "cases:write", List.of("cases:write"), 5, "high", true)));
    }

    @Test
    void incompleteInputIsDenied() {
        // required_scope and scopes absent: the rule is undefined, the default applies.
        assertFalse(allowed(Map.of("action", "call_tool", "budget_remaining", 5)));
    }

    @Test
    void failsClosedWhenSidecarIsStopped() throws Exception {
        assertTrue(allowed(input("search", "cases:read", List.of("cases:read"), 5, "low", false)));
        opa.destroyForcibly();
        opa.waitFor();
        long t0 = System.nanoTime();
        assertFalse(allowed(input("search", "cases:read", List.of("cases:read"), 5, "low", false)));
        System.out.printf("OPA_FAIL_CLOSED decision after sidecar stop took %.1f ms%n",
                (System.nanoTime() - t0) / 1e6);
    }

    @Test
    void malformedBundleDoesNotStartSoNothingCanAllow() throws Exception {
        Path bad = workDir.resolve("broken.rego");
        Files.writeString(bad, resource("/opa/broken.rego"));
        Process p = new ProcessBuilder(OPA_BIN, "run", "--server", "--addr", "127.0.0.1:" + freePort(),
                "--log-level", "error", bad.toString())
                .redirectErrorStream(true)
                .redirectOutput(workDir.resolve("broken.log").toFile())
                .start();
        assertTrue(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "opa should exit on a parse error");
        assertFalse(p.exitValue() == 0, "parse error must be a non-zero exit");
        System.out.println("OPA_MALFORMED exit=" + p.exitValue() + " log=" + Files.readString(workDir.resolve("broken.log")).trim());
    }

    @Test
    void decisionLatencyTenThousandCalls() {
        Map<String, Object> allow = input("search", "cases:read", List.of("cases:read"), 5, "low", false);
        Map<String, Object> deny = input("close_case", "cases:write", List.of("cases:write"), 5, "high", false);
        for (int i = 0; i < 2_000; i++) {
            allowed(allow);
            allowed(deny);
        }
        long[] nanos = new long[10_000];
        for (int i = 0; i < nanos.length; i++) {
            boolean expected = i % 2 == 0;
            long t0 = System.nanoTime();
            boolean got = allowed(expected ? allow : deny);
            nanos[i] = System.nanoTime() - t0;
            assertEquals(expected, got);
        }
        Latency.report("opa-sidecar-http-localhost", nanos);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OpaSidecarTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
