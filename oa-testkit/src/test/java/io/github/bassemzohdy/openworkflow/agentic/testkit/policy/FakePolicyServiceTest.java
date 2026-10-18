package io.github.bassemzohdy.openworkflow.agentic.testkit.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.testkit.policy.FakePolicyService.Request;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakePolicyServiceTest {

    private FakePolicyService policy;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        policy = new FakePolicyService().start();
    }

    @AfterEach
    void stop() {
        policy.close();
    }

    @Test
    void scriptedDecisionsAreReturnedInOrderAndRequestsRecorded() throws Exception {
        policy.enqueue(FakePolicyService.allow(), FakePolicyService.deny());
        assertEquals(true, decide("authz/allow", "{\"input\":{\"tool\":\"crm.search\"}}"));
        assertEquals(false, decide("authz/allow", "{\"input\":{\"tool\":\"crm.delete\"}}"));
        assertEquals(0, policy.remainingScript());
        assertEquals(2, policy.requests().size());
        Request first = policy.requests().get(0);
        assertEquals("authz/allow", first.path());
        assertEquals("crm.search", first.input().path("tool").asText());
    }

    @Test
    void anUnscriptedRequestIsDeniedByDefault() throws Exception {
        assertEquals(false, decide("authz/allow", "{\"input\":{}}"));
    }

    @Test
    void defaultBehaviorCanBeChanged() throws Exception {
        policy.defaultTo(FakePolicyService.allow());
        assertEquals(true, decide("authz/allow", "{\"input\":{}}"));
    }

    @Test
    void anErrorStatusIsReturnedWithoutADecision() throws Exception {
        policy.enqueue(FakePolicyService.error(503));
        HttpResponse<String> r = post("authz/allow", "{\"input\":{}}", Duration.ofSeconds(5));
        assertEquals(503, r.statusCode());
        assertTrue(!r.body().contains("result"), r.body());
    }

    @Test
    void aStallLongerThanTheCallersTimeoutLooksLikeATimeout() throws Exception {
        policy.enqueue(FakePolicyService.timeoutAfter(Duration.ofSeconds(3)));
        assertThrows(java.net.http.HttpTimeoutException.class,
                () -> post("authz/allow", "{\"input\":{}}", Duration.ofMillis(300)));
    }

    @Test
    void aStallShorterThanTheTimeoutStillAnswers() throws Exception {
        policy.enqueue(FakePolicyService.timeoutAfter(Duration.ofMillis(100)));
        assertEquals(true, decide("authz/allow", "{\"input\":{}}"));
    }

    private boolean decide(String path, String body) throws Exception {
        HttpResponse<String> r = post(path, body, Duration.ofSeconds(5));
        assertEquals(200, r.statusCode(), r.body());
        return json.readTree(r.body()).path("result").asBoolean();
    }

    private HttpResponse<String> post(String path, String body, Duration timeout) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(policy.baseUrl() + "/v1/data/" + path))
                .timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
