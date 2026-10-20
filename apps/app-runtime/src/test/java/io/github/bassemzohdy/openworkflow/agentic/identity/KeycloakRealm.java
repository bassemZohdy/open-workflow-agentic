package io.github.bassemzohdy.openworkflow.agentic.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the realm of docs/identity.md section 9 on a running Keycloak through its admin REST API, and makes the plain
 * token requests the tests need. Test support only. It deletes and recreates the realm {@link #REALM}, so point it at a
 * throw-away server.
 *
 * <p>Every call fails loudly: a set-up step that Keycloak refuses stops the test with the server's answer.
 */
final class KeycloakRealm {

    static final String REALM = "agentic-it";
    static final String API = "oa-api";
    static final String API_SHORT = "oa-api-short";
    static final String AGENT = "oa-agent-runtime";
    static final String AGENT_SECRET = "it-secret-for-oa-agent-runtime";
    static final String USER_TENANT = "tenant-1";
    static final String AGENT_TENANT = "tenant-agent";
    static final String USER_PASSWORD = "alice-pw";
    /** Lifetime of the short user token, in seconds. */
    static final int SHORT_LIFESPAN = 20;

    /** One HTTP answer: status and the parsed JSON body (an empty object when the body is not JSON). */
    record Answer(int status, JsonNode body) {
        String text(String field) {
            return body.path(field).asText(null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final boolean delegation;
    private String adminToken;

    /** Ids filled in while the realm is built. */
    String agentUserId;
    String aliceId;

    /**
     * @param delegation true to add the {@code may_act} claim mapper that Keycloak 26.8's delegation preview needs; the
     *                   server must then run with {@code --features=token-exchange-delegation,parameterized-scopes}
     */
    KeycloakRealm(String baseUrl, boolean delegation) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.delegation = delegation;
    }

    String issuer() {
        return base + "/realms/" + REALM;
    }

    String jwksUri() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    String tokenEndpoint() {
        return issuer() + "/protocol/openid-connect/token";
    }

    // ---- realm set-up ----------------------------------------------------------------------------------------------

    void build() {
        adminLogin();
        send("DELETE", base + "/admin/realms/" + REALM, null, adminToken);
        must(post(admin(""), Map.of("realm", REALM, "enabled", true, "accessTokenLifespan", 3600)));

        JsonNode profile = get(admin("/users/profile")).body();
        List<Object> attributes = new ArrayList<>();
        profile.path("attributes").forEach(attributes::add);
        for (String name : List.of("tenant", "sub_kind")) {
            attributes.add(Map.of("name", name, "displayName", name,
                    "permissions", Map.of("view", List.of("admin", "user"), "edit", List.of("admin")),
                    "validations", Map.of()));
        }
        Map<String, Object> updated = new LinkedHashMap<>(JSON.convertValue(profile, Map.class));
        updated.put("attributes", attributes);
        must(send("PUT", admin("/users/profile"), updated, adminToken));

        for (String scope : List.of("cases:read", "cases:write", "cases:delete")) {
            must(post(admin("/client-scopes"), Map.of("name", scope, "protocol", "openid-connect",
                    "attributes", Map.of("include.in.token.scope", "true", "display.on.consent.screen", "false"))));
        }

        List<Object> apiMappers = List.of(audience(API), audience(AGENT), userAttribute("tenant"), userAttribute("sub_kind"));
        String api = client(API, true, Map.of("directAccessGrantsEnabled", true), apiMappers,
                List.of("cases:read", "cases:write"), List.of());
        String apiShort = client(API_SHORT, true, Map.of("directAccessGrantsEnabled", true,
                "attributes", Map.of("access.token.lifespan", String.valueOf(SHORT_LIFESPAN))), apiMappers,
                List.of("cases:read", "cases:write"), List.of());
        String agent = client(AGENT, false, Map.of("serviceAccountsEnabled", true, "secret", AGENT_SECRET,
                "attributes", Map.of("standard.token.exchange.enabled", "true", "access.token.lifespan", "300")),
                List.of(userAttribute("tenant"), userAttribute("sub_kind")), List.of("cases:read", "cases:write"),
                List.of("profile", "email"));
        for (String target : List.of("tool-cases", "tool-search")) {
            client(target, false, Map.of(), List.of(), List.of(), List.of());
        }

        // the agent's own tenant and kind are attributes of its service-account user
        JsonNode serviceAccount = get(admin("/clients/" + agent + "/service-account-user")).body();
        agentUserId = serviceAccount.path("id").asText();
        Map<String, Object> sa = new LinkedHashMap<>(JSON.convertValue(serviceAccount, Map.class));
        sa.put("attributes", Map.of("tenant", List.of(AGENT_TENANT), "sub_kind", List.of("AGENT")));
        must(send("PUT", admin("/users/" + agentUserId), sa, adminToken));

        if (delegation) {
            for (String id : List.of(api, apiShort)) {
                must(post(admin("/clients/" + id + "/protocol-mappers/models"), mapper("may_act",
                        "oidc-hardcoded-claim-mapper", Map.of("claim.name", "may_act", "jsonType.label", "JSON",
                                "claim.value", "{\"sub\":\"" + agentUserId + "\"}"))));
            }
        }

        must(post(admin("/users"), Map.of("username", "alice", "enabled", true, "emailVerified", true,
                "email", "alice@example.org", "firstName", "Alice", "lastName", "Example",
                "attributes", Map.of("tenant", List.of(USER_TENANT)),
                "credentials", List.of(Map.of("type", "password", "value", USER_PASSWORD, "temporary", false)))));
        aliceId = get(admin("/users?username=alice&exact=true")).body().get(0).path("id").asText();
        for (String target : List.of("tool-cases", "tool-search")) {
            String uuid = get(admin("/clients?clientId=" + target)).body().get(0).path("id").asText();
            must(post(admin("/clients/" + uuid + "/roles"), Map.of("name", "invoke")));
            JsonNode role = get(admin("/clients/" + uuid + "/roles/invoke")).body();
            must(post(admin("/users/" + aliceId + "/role-mappings/clients/" + uuid), List.of(role)));
        }
    }

    private String client(String clientId, boolean publicClient, Map<String, Object> extra, List<Object> mappers,
                          List<String> optionalScopes, List<String> dropDefaultScopes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("enabled", true);
        body.put("protocol", "openid-connect");
        body.put("publicClient", publicClient);
        body.put("standardFlowEnabled", false);
        body.putAll(extra);
        must(post(admin("/clients"), body));
        String uuid = get(admin("/clients?clientId=" + clientId)).body().get(0).path("id").asText();
        for (Object mapper : mappers) {
            must(post(admin("/clients/" + uuid + "/protocol-mappers/models"), mapper));
        }
        JsonNode scopes = get(admin("/client-scopes")).body();
        for (String name : optionalScopes) {
            must(send("PUT", admin("/clients/" + uuid + "/optional-client-scopes/" + scopeId(scopes, name)), null,
                    adminToken));
        }
        JsonNode defaults = get(admin("/clients/" + uuid + "/default-client-scopes")).body();
        for (JsonNode scope : defaults) {
            if (dropDefaultScopes.contains(scope.path("name").asText())) {
                must(send("DELETE", admin("/clients/" + uuid + "/default-client-scopes/" + scope.path("id").asText()),
                        null, adminToken));
            }
        }
        return uuid;
    }

    private static String scopeId(JsonNode scopes, String name) {
        for (JsonNode scope : scopes) {
            if (name.equals(scope.path("name").asText())) {
                return scope.path("id").asText();
            }
        }
        throw new IllegalStateException("no client scope " + name);
    }

    private static Map<String, Object> mapper(String name, String type, Map<String, String> config) {
        Map<String, String> all = new LinkedHashMap<>(config);
        all.put("access.token.claim", "true");
        all.put("id.token.claim", "false");
        all.put("userinfo.token.claim", "false");
        return Map.of("name", name, "protocol", "openid-connect", "protocolMapper", type, "config", all);
    }

    private static Map<String, Object> audience(String clientId) {
        return mapper("aud-" + clientId, "oidc-audience-mapper", Map.of("included.client.audience", clientId));
    }

    private static Map<String, Object> userAttribute(String claim) {
        return mapper(claim, "oidc-usermodel-attribute-mapper",
                Map.of("user.attribute", claim, "claim.name", claim, "jsonType.label", "String"));
    }

    // ---- token requests --------------------------------------------------------------------------------------------

    /** Alice's access token by password grant against {@code client}, with the scopes {@code cases:read cases:write}. */
    Answer userToken(String client) {
        return form(tokenEndpoint(), Map.of("grant_type", "password", "client_id", client, "username", "alice",
                "password", USER_PASSWORD, "scope", "cases:read cases:write"), null);
    }

    /** The access token of the agent's service account (client credentials). */
    Answer workloadToken() {
        return form(tokenEndpoint(), Map.of("grant_type", "client_credentials"), basic(AGENT, AGENT_SECRET));
    }

    /** A raw RFC 8693 request as the agent client; {@code extra} is added to the standard fields. */
    Answer exchange(String subjectToken, Map<String, String> extra) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        fields.put("subject_token", subjectToken);
        fields.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        fields.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        fields.putAll(extra);
        return form(tokenEndpoint(), fields, basic(AGENT, AGENT_SECRET));
    }

    /** Ends alice's session behind {@code refreshToken}. */
    void logout(String client, String refreshToken) {
        form(issuer() + "/protocol/openid-connect/logout", Map.of("client_id", client, "refresh_token", refreshToken), null);
    }

    /** The payload of a JWT, unverified: only for looking at what the server put in a token. */
    static JsonNode payload(String jwt) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        } catch (IOException e) {
            throw new IllegalArgumentException("not a JWT", e);
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private void adminLogin() {
        Answer answer = form(base + "/realms/master/protocol/openid-connect/token", Map.of("grant_type", "password",
                "client_id", "admin-cli", "username", System.getenv().getOrDefault("KEYCLOAK_ADMIN", "admin"),
                "password", System.getenv().getOrDefault("KEYCLOAK_ADMIN_PASSWORD", "admin")), null);
        must(answer);
        adminToken = answer.text("access_token");
    }

    private String admin(String path) {
        return base + "/admin/realms" + (path.isEmpty() ? "" : "/" + REALM + path);
    }

    private Answer get(String url) {
        return must(send("GET", url, null, adminToken));
    }

    private Answer post(String url, Object body) {
        return send("POST", url, body, adminToken);
    }

    private static Answer must(Answer answer) {
        if (answer.status() >= 300) {
            throw new IllegalStateException("Keycloak answered " + answer.status() + ": " + answer.body());
        }
        return answer;
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private Answer form(String url, Map<String, String> fields, String authorization) {
        StringBuilder body = new StringBuilder();
        fields.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return run(request.build());
    }

    private Answer send(String method, String url, Object json, String bearer) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + bearer);
            if (json == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(json)));
            }
            return run(request.build());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Answer run(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode body;
            try {
                body = text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
            } catch (IOException notJson) {
                body = JSON.createObjectNode();
            }
            return new Answer(response.statusCode(), body);
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak could not be reached at " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
