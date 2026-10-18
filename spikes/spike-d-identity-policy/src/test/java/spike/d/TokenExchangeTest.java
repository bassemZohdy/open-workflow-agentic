package spike.d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 8693 token exchange against MiniAuthorizationServer (in-process HTTP, JDK httpserver).
 * Proves the client request/response shape and our delegation rules. Says nothing about Keycloak.
 */
class TokenExchangeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private MiniAuthorizationServer as;
    private long base;
    private String userToken;
    private String agentToken;

    record Result(int status, JsonNode body) {
        String error() {
            return body.path("error").asText();
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        as = new MiniAuthorizationServer();
        base = as.now();
        // The user signed in to the portal; the token is for the gateway, not for any tool.
        userToken = as.issue("bassem", "oa-gateway", "cases:read cases:write", base + 14_400, null);
        // The agent runtime's own workload identity (service identity, not a user token).
        // Long-lived here so that grant expiry, not the actor token, is what the long-wait test exercises.
        agentToken = as.issue("agent-42", "oa-agent-runtime", "", base + 14_400, null);
        // The user delegated read access only, for 30 minutes.
        as.grant("bassem", "agent-42", Set.of("cases:read"), base + 1_800);
    }

    @AfterEach
    void stopServer() {
        as.close();
    }

    private Result exchange(Map<String, String> params) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", MiniAuthorizationServer.TOKEN_EXCHANGE);
        form.put("subject_token_type", MiniAuthorizationServer.ACCESS_TOKEN_TYPE);
        form.put("requested_token_type", MiniAuthorizationServer.ACCESS_TOKEN_TYPE);
        form.put("subject_token", userToken);
        form.put("actor_token", agentToken);
        form.putAll(params);
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(as.tokenEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Result(r.statusCode(), JSON.readTree(r.body()));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> toolRequest(String audience, String scope) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("audience", audience);
        if (scope != null) {
            m.put("scope", scope);
        }
        return m;
    }

    @Test
    void exchangeNarrowsAudienceAndScopeAndRecordsTheDelegationChain() throws Exception {
        Result r = exchange(toolRequest("tool-search", "cases:read"));

        assertEquals(200, r.status());
        String token = r.body().path("access_token").asText();
        assertNotEquals(userToken, token, "the user token must not be passed through");
        JsonNode claims = as.verify(token, "tool-search");
        assertEquals("bassem", claims.path("sub").asText());
        assertEquals("agent-42", claims.path("act").path("sub").asText());
        assertEquals("cases:read", claims.path("scope").asText());
        assertTrue(claims.path("exp").asLong() - as.now() <= MiniAuthorizationServer.MAX_DELEGATED_LIFETIME_SECONDS);
        assertFalse(r.body().has("refresh_token"), "no refresh token is issued by default");
        assertEquals("Bearer", r.body().path("token_type").asText());
    }

    @Test
    void refusesScopeExpansionBeyondTheGrant() throws Exception {
        // The user holds cases:write but the grant only includes cases:read.
        Result r = exchange(toolRequest("tool-search", "cases:read cases:write"));
        assertEquals(400, r.status());
        assertEquals("invalid_scope", r.error());
    }

    @Test
    void refusesScopeNobodyHolds() throws Exception {
        Result r = exchange(toolRequest("tool-search", "admin"));
        assertEquals("invalid_scope", r.error());
    }

    @Test
    void refusesAnAudienceThatIsNotRegistered() throws Exception {
        Result r = exchange(toolRequest("payroll", "cases:read"));
        assertEquals(400, r.status());
        assertEquals("invalid_target", r.error());
    }

    @Test
    void refusesAnExpiredSubjectToken() throws Exception {
        userToken = as.issue("bassem", "oa-gateway", "cases:read", base - 10, null);
        Result r = exchange(toolRequest("tool-search", "cases:read"));
        assertEquals("invalid_grant", r.error());
    }

    @Test
    void refusesWhenNoDelegationGrantExists() throws Exception {
        as.revoke("bassem", "agent-42");
        Result r = exchange(toolRequest("tool-search", "cases:read"));
        assertEquals("invalid_grant", r.error());
    }

    @Test
    void grantExpiryForcesReauthorizationEvenWhileTheUserTokenIsStillValid() throws Exception {
        assertEquals(200, exchange(toolRequest("tool-search", "cases:read")).status());

        // Two hours later: the user token (exp +4h) is still valid, the grant (exp +30min) is not.
        as.setNow(base + 7_200);
        Result late = exchange(toolRequest("tool-search", "cases:read"));
        assertEquals("invalid_grant", late.error());
        assertTrue(late.body().path("error_description").asText().contains("re-authorization"));
    }

    @Test
    void refusesAnIdTokenAsTheSubject() throws Exception {
        Map<String, String> params = new LinkedHashMap<>(toolRequest("tool-search", "cases:read"));
        params.put("subject_token_type", MiniAuthorizationServer.ID_TOKEN_TYPE);
        assertEquals("invalid_request", exchange(params).error());
    }

    @Test
    void resourceServerRejectsTheUserTokenUsedDirectly() throws Exception {
        // Token passthrough: the user's gateway token presented to a tool must not be accepted.
        assertThrows(IllegalArgumentException.class, () -> as.verify(userToken, "tool-search"));
    }

    @Test
    void resourceServerAcceptsOnlyTheTokenIssuedForItsAudience() throws Exception {
        String token = exchange(toolRequest("tool-search", "cases:read")).body().path("access_token").asText();
        as.verify(token, "tool-search");
        assertThrows(IllegalArgumentException.class, () -> as.verify(token, "tool-close-case"));
    }

    @Test
    void agentToAgentDelegationCanOnlyNarrow() throws Exception {
        String first = exchange(toolRequest("tool-search", "cases:read")).body().path("access_token").asText();

        // A sub-agent asks for a narrower or equal scope from the delegated token.
        String childAgent = as.issue("agent-7", "oa-agent-runtime", "", base + 3_600, null);
        as.grant("bassem", "agent-7", Set.of("cases:read"), base + 1_800);
        Map<String, String> narrow = new LinkedHashMap<>(toolRequest("tool-search", "cases:read"));
        narrow.put("subject_token", first);
        narrow.put("actor_token", childAgent);
        Result ok = exchange(narrow);
        assertEquals(200, ok.status());
        JsonNode claims = as.verify(ok.body().path("access_token").asText(), "tool-search");
        assertEquals("agent-7", claims.path("act").path("sub").asText());
        assertEquals("agent-42", claims.path("act").path("act").path("sub").asText());

        Map<String, String> widen = new LinkedHashMap<>(toolRequest("tool-search", "cases:read cases:write"));
        widen.put("subject_token", first);
        widen.put("actor_token", childAgent);
        assertEquals("invalid_scope", exchange(widen).error());
    }
}
