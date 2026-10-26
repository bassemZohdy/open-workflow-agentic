package io.github.bassemzohdy.openworkflow.agentic.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Timeout diagnostic for {@link IdentityRecoveryIT}: one bounded read of the dev probe's engine snapshot. It is evidence
 * only: it never throws, and anything other than a well-formed answer collapses to one fixed marker, so a failure of the
 * diagnostic cannot replace the original test failure and no response, exception or credential text is copied.
 */
final class RecoveryEngineSnapshot {

    static final String UNAVAILABLE = "engine snapshot unavailable";
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STATUS = Pattern.compile("[A-Z_]{1,32}");

    private RecoveryEngineSnapshot() { }

    static String describe(String baseUrl, String definition, String version, String engineInstanceId) {
        return describe(baseUrl, definition, version, engineInstanceId, TIMEOUT);
    }

    static String describe(String baseUrl, String definition, String version, String engineInstanceId,
                           Duration timeout) {
        try {
            URI uri = URI.create(baseUrl + "/dev/lifecycle/engine?definition=" + encode(definition)
                    + "&version=" + encode(version) + "&instanceId=" + encode(engineInstanceId));
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return UNAVAILABLE;
            }
            JsonNode body = JSON.readTree(response.body());
            JsonNode present = body.path("present");
            String status = body.path("status").asText("");
            if (!present.isBoolean() || !STATUS.matcher(status).matches()) {
                return UNAVAILABLE;
            }
            return "engine snapshot: present=" + present.asBoolean() + " status=" + status;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return UNAVAILABLE;
        } catch (Exception | LinkageError any) {
            return UNAVAILABLE;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
