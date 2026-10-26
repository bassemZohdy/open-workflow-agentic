package io.github.bassemzohdy.openworkflow.agentic.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.EngineStatus;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Timeout diagnostic for {@link IdentityRecoveryIT}: one bounded read of the dev probe's engine snapshot. It is evidence
 * only: it never throws, and anything other than a well-formed, consistent answer collapses to one fixed marker, so a
 * failure of the diagnostic cannot replace the original test failure and no response, exception or credential text is
 * copied. The whole exchange (connect, headers and body) is bounded by one deadline.
 */
final class RecoveryEngineSnapshot {

    static final String UNAVAILABLE = "engine snapshot unavailable";
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ABSENT = "ABSENT";
    private static final long MAX_BODY_BYTES = 4096;
    /** Exactly the names the engine can report; any other text, however uppercase, is never echoed. */
    private static final Set<String> PRESENT_STATUSES = Arrays.stream(EngineStatus.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final HttpResponse.BodyHandler<String> BOUNDED_BODY = info ->
            info.statusCode() == 200 && info.headers().firstValueAsLong("content-length").orElse(0) <= MAX_BODY_BYTES
                    ? HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
                    : HttpResponse.BodySubscribers.replacing(null);

    private RecoveryEngineSnapshot() { }

    static String describe(String baseUrl, String definition, String version, String engineInstanceId) {
        return describe(baseUrl, definition, version, engineInstanceId, TIMEOUT);
    }

    static String describe(String baseUrl, String definition, String version, String engineInstanceId,
                           Duration timeout) {
        HttpClient client = null;
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            URI uri = URI.create(baseUrl + "/dev/lifecycle/engine?definition=" + encode(definition)
                    + "&version=" + encode(version) + "&instanceId=" + encode(engineInstanceId));
            client = HttpClient.newBuilder().connectTimeout(timeout).build();
            // HttpRequest.timeout may only bound the wait for headers, so the total is bounded on the future instead.
            pending = client.sendAsync(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(), BOUNDED_BODY);
            HttpResponse<String> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || response.body() == null) {
                return UNAVAILABLE;
            }
            JsonNode body = JSON.readTree(response.body());
            JsonNode present = body.path("present");
            String status = body.path("status").asText("");
            if (!present.isBoolean()) {
                return UNAVAILABLE;
            }
            boolean consistent = present.asBoolean() ? PRESENT_STATUSES.contains(status) : ABSENT.equals(status);
            return consistent ? "engine snapshot: present=" + present.asBoolean() + " status=" + status : UNAVAILABLE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return UNAVAILABLE;
        } catch (Exception | LinkageError any) {
            return UNAVAILABLE;
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
            if (client != null) {
                client.shutdownNow(); // not close(): that waits for the very exchange that just missed its deadline
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
