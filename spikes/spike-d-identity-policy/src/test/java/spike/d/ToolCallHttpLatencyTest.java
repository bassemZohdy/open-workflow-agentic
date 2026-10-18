package spike.d;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** End-to-end latency of the Quarkus enforcement point (HTTP in, HttpSecurityPolicy, Cedar, tool stub). */
@QuarkusTest
class ToolCallHttpLatencyTest {

    @TestHTTPResource("/tools")
    URL tools;

    @Test
    @TestSecurity(user = "agent-42", roles = {"cases:read"})
    void tenThousandCallsThroughTheHttpPolicy() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI allowed = URI.create(tools + "/search/call");
        URI denied = URI.create(tools + "/close_case/call");
        HttpRequest okReq = HttpRequest.newBuilder(allowed).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpRequest noReq = HttpRequest.newBuilder(denied).POST(HttpRequest.BodyPublishers.noBody()).build();

        for (int i = 0; i < 2_000; i++) {
            http.send(okReq, HttpResponse.BodyHandlers.discarding());
            http.send(noReq, HttpResponse.BodyHandlers.discarding());
        }
        long[] nanos = new long[10_000];
        for (int i = 0; i < nanos.length; i++) {
            boolean expectAllow = i % 2 == 0;
            long t0 = System.nanoTime();
            int status = http.send(expectAllow ? okReq : noReq, HttpResponse.BodyHandlers.discarding()).statusCode();
            nanos[i] = System.nanoTime() - t0;
            assertEquals(expectAllow ? 200 : 403, status);
        }
        Latency.report("quarkus-http-policy-end-to-end", nanos);
    }
}
