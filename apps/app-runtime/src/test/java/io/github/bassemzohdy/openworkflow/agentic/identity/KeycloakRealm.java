package io.github.bassemzohdy.openworkflow.agentic.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.identity.KeycloakSupport.Config;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a disposable realm on a running Keycloak through its admin REST API and makes the token requests the tests
 * need. Test support only. Every instance creates its own uniquely named realm ({@link #realm}) and {@link #close()}
 * deletes it, so tests never share state and never touch another realm of the server.
 *
 * <p>Delegation profile (Keycloak 26.8 with {@code token-exchange-delegation,parameterized-scopes}): authority comes
 * from the product's real mechanism and from nothing fixed in a token mapper. The realm enables admin permissions
 * (FGAP v2) and creates a {@code delegate} permission on the user alice for the client policy of the agent runtime;
 * the consent-required client {@link #CONSOLE} lets alice log in with the scope
 * {@code delegation:client:oa-agent-runtime} through the browser flow, driven here by plain HTTP (login form, consent
 * form). Without the permission the server drops the scope; without alice's consent it issues no code at all.
 *
 * <p>Every call fails loudly: a set-up step that Keycloak refuses stops the test with the server's answer.
 */
final class KeycloakRealm implements AutoCloseable {

    static final String API = "oa-api";
    static final String CONSOLE = "oa-console";
    static final String CONSOLE_SHORT = "oa-console-short";
    static final String AGENT = "oa-agent-runtime";
    static final String AGENT_SECRET = "it-secret-for-oa-agent-runtime";
    static final String OTHER_AGENT = "oa-other-agent";
    static final String OTHER_AGENT_SECRET = "it-secret-for-oa-other-agent";
    static final String USER_TENANT = "tenant-1";
    static final String AGENT_TENANT = "tenant-agent";
    static final String USER_PASSWORD = "alice-pw";
    /** Lifetime of the short tokens, in seconds. Below the 300 seconds of an exchanged token on purpose. */
    static final int SHORT_LIFESPAN = 100;
    static final String REDIRECT = "http://127.0.0.1:9/callback";

    /** One HTTP answer: status and the parsed JSON body (an empty object when the body is not JSON). */
    record Answer(int status, JsonNode body) {
        String text(String field) {
            return body.path(field).asText(null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*\\baction=\"([^\"]+)\"");
    private static final Pattern CONSENT_CODE = Pattern.compile(
            "name=\"code\"\\s+value=\"([^\"]*)\"|value=\"([^\"]*)\"\\s+name=\"code\"");

    private final String base;
    private final Config config;
    private final boolean delegation;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private String adminToken;

    /** The name of this instance's realm. */
    final String realm = "agentic-it-" + UUID.randomUUID().toString().substring(0, 8);
    /** Ids filled in while the realm is built. */
    String agentUserId;
    String otherAgentUserId;
    String aliceId;

    KeycloakRealm(Config config) {
        this.config = config;
        this.base = config.url().endsWith("/") ? config.url().substring(0, config.url().length() - 1) : config.url();
        this.delegation = "delegation".equals(config.profile());
    }

    String issuer() {
        return base + "/realms/" + realm;
    }

    String jwksUri() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    String tokenEndpoint() {
        return issuer() + "/protocol/openid-connect/token";
    }

    @Override
    public void close() {
        try {
            send("DELETE", base + "/admin/realms/" + realm, null, adminToken);
        } catch (RuntimeException ignored) {
            // a disposable server is thrown away anyway
        }
    }

    // ---- realm set-up ----------------------------------------------------------------------------------------------

    void build() {
        adminLogin();
        Map<String, Object> realmBody = new LinkedHashMap<>();
        realmBody.put("realm", realm);
        realmBody.put("enabled", true);
        realmBody.put("accessTokenLifespan", 3600);
        realmBody.put("ssoSessionIdleTimeout", 7200);
        if (delegation) {
            realmBody.put("adminPermissionsEnabled", true);
        }
        must(post(base + "/admin/realms", realmBody));
        checkProfile();

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

        List<Object> userClaims = List.of(audience(AGENT), userAttribute("tenant"), userAttribute("sub_kind"));
        List<String> caseScopes = List.of("cases:read", "cases:write", "cases:delete");
        // cases:delete exists as a scope but is never offered to a user client: alice cannot hold it
        client(API, true, Map.of("directAccessGrantsEnabled", true), userClaims, List.of("cases:read", "cases:write"),
                List.of());
        Map<String, Object> consentClient = new LinkedHashMap<>();
        consentClient.put("standardFlowEnabled", true);
        consentClient.put("consentRequired", true);
        consentClient.put("redirectUris", List.of(REDIRECT));
        client(CONSOLE, true, consentClient, userClaims, List.of("cases:read", "cases:write"), List.of());
        Map<String, Object> shortClient = new LinkedHashMap<>(consentClient);
        shortClient.put("attributes", Map.of("access.token.lifespan", String.valueOf(SHORT_LIFESPAN)));
        client(CONSOLE_SHORT, true, shortClient, userClaims, List.of("cases:read", "cases:write"), List.of());
        String agent = client(AGENT, false, Map.of("serviceAccountsEnabled", true, "secret", AGENT_SECRET,
                "attributes", Map.of("standard.token.exchange.enabled", "true", "access.token.lifespan", "300")),
                List.of(audience(AGENT), userAttribute("tenant"), userAttribute("sub_kind")), caseScopes,
                List.of("profile", "email"));
        String other = client(OTHER_AGENT, false, Map.of("serviceAccountsEnabled", true, "secret", OTHER_AGENT_SECRET,
                "attributes", Map.of("standard.token.exchange.enabled", "true", "access.token.lifespan", "300")),
                List.of(audience(AGENT), userAttribute("tenant"), userAttribute("sub_kind")), caseScopes,
                List.of("profile", "email"));
        for (String target : List.of("tool-cases", "tool-search")) {
            client(target, false, Map.of(), List.of(), List.of(), List.of());
        }

        // the agents' own tenant and kind are attributes of their service-account users
        agentUserId = agentAttributes(agent, "AGENT");
        otherAgentUserId = agentAttributes(other, "AGENT");

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

        if (delegation) {
            allowDelegation(agent);
        }
    }

    /** The server must be the one this realm is built for: delegation scopes exist exactly in the delegation profile. */
    private void checkProfile() {
        boolean hasDelegationScope = false;
        for (JsonNode scope : get(admin("/client-scopes")).body()) {
            if ("delegation:client".equals(scope.path("name").asText())) {
                hasDelegationScope = true;
            }
        }
        if (hasDelegationScope != delegation) {
            throw new IllegalStateException("keycloak.profile=" + config.profile() + " but the server "
                    + (hasDelegationScope ? "has" : "lacks") + " the delegation client scopes: wrong image or features");
        }
    }

    private String agentAttributes(String clientUuid, String kind) {
        JsonNode serviceAccount = get(admin("/clients/" + clientUuid + "/service-account-user")).body();
        String id = serviceAccount.path("id").asText();
        Map<String, Object> sa = new LinkedHashMap<>(JSON.convertValue(serviceAccount, Map.class));
        sa.put("attributes", Map.of("tenant", List.of(AGENT_TENANT), "sub_kind", List.of(kind)));
        must(send("PUT", admin("/users/" + id), sa, adminToken));
        return id;
    }

    /** The FGAP v2 permission: the client policy of the agent runtime may {@code delegate} for alice, and only alice. */
    private void allowDelegation(String agentUuid) {
        String permissions = get(admin("/clients?clientId=admin-permissions")).body().get(0).path("id").asText();
        String server = admin("/clients/" + permissions + "/authz/resource-server");
        must(post(server + "/policy/client", Map.of("name", "agent-runtime", "type", "client", "logic", "POSITIVE",
                "decisionStrategy", "UNANIMOUS", "clients", List.of(agentUuid))));
        String policy = null;
        for (JsonNode p : get(server + "/policy").body()) {
            if ("agent-runtime".equals(p.path("name").asText())) {
                policy = p.path("id").asText();
            }
        }
        if (policy == null) {
            throw new IllegalStateException("the client policy was not created");
        }
        must(post(server + "/permission/scope", Map.of("name", "delegate-for-alice", "type", "scope",
                "resourceType", "Users", "resources", List.of(aliceId), "scopes", List.of("delegate"),
                "policies", List.of(policy), "decisionStrategy", "UNANIMOUS")));
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

    /** Alice's access token by password grant against {@link #API}, with the scopes {@code cases:read cases:write}. */
    Answer userToken() {
        return form(tokenEndpoint(), Map.of("grant_type", "password", "client_id", API, "username", "alice",
                "password", USER_PASSWORD, "scope", "cases:read cases:write"), null);
    }

    /** The access token of the agent runtime's service account (client credentials). */
    Answer workloadToken() {
        return form(tokenEndpoint(), Map.of("grant_type", "client_credentials"), basic(AGENT, AGENT_SECRET));
    }

    /** The access token of the other agent's service account: a valid workload identity that is not the runtime's. */
    Answer otherWorkloadToken() {
        return form(tokenEndpoint(), Map.of("grant_type", "client_credentials"),
                basic(OTHER_AGENT, OTHER_AGENT_SECRET));
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

    /** What the consent screen decided: the token answer after consent, or the OAuth error after refusal. */
    record Login(boolean consentShown, boolean consentAnswered, Answer token, String error) { }

    /**
     * Alice logs in through the browser flow with {@code delegation:client:<actorClient>} (plus the case scopes):
     * login form, then the consent screen when the server shows one, answered with {@code consent}. Returns the token
     * of the authorization code, or the error of the redirect when consent was refused.
     */
    Login delegatedLogin(String client, String actorClient, boolean consent) {
        Map<String, String> cookies = new LinkedHashMap<>();
        String scope = "openid cases:read cases:write delegation:client:" + actorClient;
        String authUrl = issuer() + "/protocol/openid-connect/auth?" + query(Map.of("client_id", client,
                "response_type", "code", "redirect_uri", REDIRECT, "scope", scope, "state", "it"));
        HttpResponse<String> page = browser("GET", authUrl, null, cookies);
        String formAction = formAction(page, "login form");
        HttpResponse<String> next = browser("POST", formAction, Map.of("username", "alice", "password", USER_PASSWORD,
                "credentialId", ""), cookies);
        if (next.statusCode() / 100 == 3 && !next.headers().firstValue("location").orElse("").startsWith(REDIRECT)) {
            next = browser("GET", absolute(next.headers().firstValue("location").orElseThrow()), null, cookies);
        }
        boolean consentShown = false;
        if (next.statusCode() == 200 && next.body().contains("name=\"accept\"")) {
            consentShown = true;
            Map<String, String> answer = new LinkedHashMap<>();
            answer.put(consent ? "accept" : "cancel", consent ? "Yes" : "No");
            Matcher code = CONSENT_CODE.matcher(next.body());
            if (code.find()) {
                answer.put("code", code.group(1) != null ? code.group(1) : code.group(2));
            }
            next = browser("POST", formAction(next, "consent form"), answer, cookies);
        }
        String location = next.headers().firstValue("location").orElse("");
        if (!location.startsWith(REDIRECT)) {
            throw new IllegalStateException("the login flow did not end in the redirect: HTTP " + next.statusCode()
                    + " " + location);
        }
        Map<String, String> result = parseQuery(URI.create(location).getRawQuery());
        if (result.containsKey("error")) {
            return new Login(consentShown, consent, null, result.get("error"));
        }
        Answer token = form(tokenEndpoint(), Map.of("grant_type", "authorization_code", "code", result.get("code"),
                "client_id", client, "redirect_uri", REDIRECT), null);
        return new Login(consentShown, consent, token, null);
    }

    /** Ends alice's sessions; every access token of those sessions is then refused by the exchange. */
    void logoutAlice() {
        must(post(admin("/users/" + aliceId + "/logout"), null));
    }

    /**
     * Rotates the realm's signing key: a new RSA key becomes the active one and the previous key is switched off, so it
     * leaves the published JWKS. Tokens signed before keep their signature but no longer verify once a verifier has
     * refreshed its key set.
     */
    void rotateKeys() {
        JsonNode components = get(admin("/components?type=org.keycloak.keys.KeyProvider")).body();
        String realmId = get(base + "/admin/realms/" + realm).body().path("id").asText();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("priority", List.of("500"));
        config.put("enabled", List.of("true"));
        config.put("active", List.of("true"));
        config.put("keySize", List.of("2048"));
        must(post(admin("/components"), Map.of("name", "rotated-rsa", "providerId", "rsa-generated",
                "providerType", "org.keycloak.keys.KeyProvider", "parentId", realmId, "config", config)));
        for (JsonNode component : components) {
            if ("rsa-generated".equals(component.path("providerId").asText())) {
                Map<String, Object> retired = new LinkedHashMap<>(JSON.convertValue(component, Map.class));
                Map<String, Object> retiredConfig = new LinkedHashMap<>(JSON.convertValue(component.path("config"),
                        Map.class));
                retiredConfig.put("enabled", List.of("false"));
                retiredConfig.put("active", List.of("false"));
                retired.put("config", retiredConfig);
                must(send("PUT", admin("/components/" + component.path("id").asText()), retired, adminToken));
            }
        }
    }

    /** The payload of a JWT, unverified: only for looking at what the server put in a token. */
    static JsonNode payload(String jwt) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        } catch (IOException e) {
            throw new IllegalArgumentException("not a JWT", e);
        }
    }

    // ---- the browser flow ------------------------------------------------------------------------------------------

    /**
     * One step of the browser flow with a minimal cookie store. Keycloak marks its flow cookies {@code Secure} even on
     * http://127.0.0.1, which browsers treat as a secure context and send; the JDK cookie manager would not, so the
     * cookies are kept here by name.
     */
    private HttpResponse<String> browser(String method, String url, Map<String, String> form,
                                         Map<String, String> cookies) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (!cookies.isEmpty()) {
            StringBuilder header = new StringBuilder();
            cookies.forEach((k, v) -> header.append(header.length() == 0 ? "" : "; ").append(k).append('=').append(v));
            request.header("Cookie", header.toString());
        }
        if (form == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, HttpRequest.BodyPublishers.ofString(query(form)));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String setCookie : response.headers().allValues("set-cookie")) {
                String pair = setCookie.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String value = pair.substring(eq + 1);
                    if (value.isEmpty() || setCookie.contains("Max-Age=0")) {
                        cookies.remove(pair.substring(0, eq));
                    } else {
                        cookies.put(pair.substring(0, eq), value);
                    }
                }
            }
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak could not be reached at " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String formAction(HttpResponse<String> page, String what) {
        Matcher m = FORM_ACTION.matcher(page.body());
        if (page.statusCode() != 200 || !m.find()) {
            throw new IllegalStateException("no " + what + " (HTTP " + page.statusCode() + ")");
        }
        return absolute(m.group(1).replace("&amp;", "&"));
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : URI.create(base + "/").resolve(location).toString();
    }

    private static String query(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        fields.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return body.toString();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private void adminLogin() {
        Answer answer = form(base + "/realms/master/protocol/openid-connect/token", Map.of("grant_type", "password",
                "client_id", "admin-cli", "username", config.adminUser(), "password", config.adminPassword()), null);
        if (answer.status() != 200) {
            throw new IllegalStateException("the Keycloak admin login failed with HTTP " + answer.status()
                    + "; check keycloak.admin.user / keycloak.admin.password");
        }
        adminToken = answer.text("access_token");
    }

    private String admin(String path) {
        return base + "/admin/realms/" + realm + path;
    }

    private Answer get(String url) {
        return must(send("GET", url, null, adminToken));
    }

    private Answer post(String url, Object body) {
        return send("POST", url, body == null ? Map.of() : body, adminToken);
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
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(query(fields)));
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
