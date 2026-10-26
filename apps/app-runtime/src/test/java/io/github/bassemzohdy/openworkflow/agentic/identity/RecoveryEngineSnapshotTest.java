package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RecoveryEngineSnapshotTest {

    private static final String PRIVATE = "SECRET-CANARY-java.lang.IllegalStateException: private detail";
    private static final Duration SHORT = Duration.ofMillis(500);

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String serve(int status, String body, long delayMillis, AtomicReference<String> seen) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            if (seen != null) {
                seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException clientGone) {
                // the caller timed out first
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String describe(String base) {
        return RecoveryEngineSnapshot.describe(base, "agentic/lifecycle-probe", "0.1.0", "engine-1", SHORT);
    }

    @Test
    void reportsTheActualSnapshotFromTheExactProbeRequest() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String base = serve(200, "{\"present\":true,\"status\":\"WAITING\"}", 0, seen);
        assertEquals("engine snapshot: present=true status=WAITING", describe(base));
        assertEquals("GET /dev/lifecycle/engine?definition=agentic%2Flifecycle-probe&version=0.1.0&instanceId=engine-1",
                seen.get());
    }

    @Test
    void reportsAbsentWithoutInferringCompletion() throws Exception {
        String base = serve(200, "{\"present\":false,\"status\":\"ABSENT\"}", 0, null);
        String text = describe(base);
        assertEquals("engine snapshot: present=false status=ABSENT", text);
        assertFalse(text.contains("COMPLETED"));
    }

    @Test
    void unavailableAbsentRouteAndMalformedAnswersCollapseToTheFixedMarkerWithoutPrivateText() throws Exception {
        String[][] cases = {
                {"503", "{\"present\":false,\"status\":\"UNAVAILABLE\",\"detail\":\"" + PRIVATE + "\"}"},
                {"404", PRIVATE},
                {"200", "not json " + PRIVATE},
                {"200", "{\"present\":\"yes\",\"status\":\"WAITING\"}"},
                {"200", "{\"present\":true,\"status\":\"" + PRIVATE + "\"}"},
                {"200", "{\"present\":true}"},
                {"200", ""},
        };
        for (String[] each : cases) {
            stop();
            String text = describe(serve(Integer.parseInt(each[0]), each[1], 0, null));
            assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, text, each[0] + " " + each[1]);
            assertFalse(text.contains("CANARY") || text.contains("private"), text);
        }
    }

    @Test
    void aStalledProbeIsAbandonedAtTheIndependentTimeout() throws Exception {
        String base = serve(200, "{\"present\":true,\"status\":\"WAITING\"}", 5_000, null);
        long start = System.nanoTime();
        String text = describe(base);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, text);
        assertTrue(elapsedMillis < 3_000, "took " + elapsedMillis + " ms");
    }

    @Test
    void transportFailuresNeverThrowAndCarryNoExceptionText() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, describe("http://127.0.0.1:" + closedPort));
        // an unusable base URL fails before any request is made
        String text = RecoveryEngineSnapshot.describe("http://bad host:1/" + PRIVATE, "d", "v", "e", SHORT);
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, text);
        assertFalse(text.contains("CANARY"));
    }
}
