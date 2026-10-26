package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.EngineStatus;
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
                {"200", "{\"present\":true,\"status\":\"PRIVATECANARY\"}"},
                {"200", "{\"present\":true,\"status\":\"WAITING_CANARY\"}"},
                {"200", "{\"present\":true,\"status\":\"ABSENT\"}"},
                {"200", "{\"present\":false,\"status\":\"COMPLETED\"}"},
                {"200", "{\"present\":false,\"status\":\"WAITING\"}"},
                {"200", "{\"present\":false,\"status\":\"PRIVATECANARY\"}"},
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
    void everyRealEngineStatusIsReportedWhenPresent() throws Exception {
        for (EngineStatus each : EngineStatus.values()) {
            stop();
            String base = serve(200, "{\"present\":true,\"status\":\"" + each + "\"}", 0, null);
            assertEquals("engine snapshot: present=true status=" + each, describe(base));
        }
    }

    /** Headers arrive at once, then the body stalls: only a whole-exchange deadline can bound this. */
    private String serveStalledBody() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 64);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(5_000);
            } catch (IOException | InterruptedException clientGone) {
                // the caller gave up first
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void aStalledBodyAfterImmediateHeadersIsAbandonedAtTheWholeSnapshotDeadline() throws Exception {
        String base = serveStalledBody();
        long start = System.nanoTime();
        String text = describe(base);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, text);
        assertTrue(elapsedMillis < 2_000, "took " + elapsedMillis + " ms");
    }

    private static final String VALID = "{\"present\":true,\"status\":\"WAITING\"}";

    /** Chunked (no Content-Length) body of exactly {@code size} bytes: a valid answer padded with spaces. */
    private String serveChunked(int size) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write((VALID + " ".repeat(size - VALID.length())).getBytes(StandardCharsets.UTF_8));
            } catch (IOException clientGone) {
                // the caller cancelled the oversized stream
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void anOversizedChunkedBodyWithoutContentLengthIsRejectedByActualBytes() throws Exception {
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, describe(serveChunked(64 * 1024)));
        stop();
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, describe(serveChunked(4097)));
    }

    @Test
    void aChunkedBodyAtTheLimitIsStillAccepted() throws Exception {
        assertEquals("engine snapshot: present=true status=WAITING", describe(serveChunked(4096)));
    }

    @Test
    void aDeclaredOverLimitBodyIsRejected() throws Exception {
        String padded = VALID + " ".repeat(5000);
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, describe(serve(200, padded, 0, null)));
    }

    @Test
    void anInterruptedCallerGetsTheMarkerPromptlyAndKeepsItsInterruptFlag() throws Exception {
        String base = serveStalledBody();
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            text.set(RecoveryEngineSnapshot.describe(base, "d", "v", "e", Duration.ofSeconds(30)));
            flag.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        Thread.sleep(300);
        long start = System.nanoTime();
        caller.interrupt();
        caller.join(3_000);
        assertFalse(caller.isAlive(), "caller still blocked");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_000);
        assertEquals(RecoveryEngineSnapshot.UNAVAILABLE, text.get());
        assertEquals(Boolean.TRUE, flag.get());
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
