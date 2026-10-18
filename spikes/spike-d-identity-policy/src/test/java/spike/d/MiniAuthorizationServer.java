package spike.d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal OAuth 2.0 token endpoint for the WP-006 spike. It implements the RFC 8693 request and
 * response shape and the delegation rules this plan needs (narrowing only, audience allow-list,
 * delegation grant with expiry, no refresh token). It is NOT Keycloak: the tests prove what our
 * client and resource checks do against this model, not what Keycloak does.
 */
final class MiniAuthorizationServer implements AutoCloseable {

    static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    static final String ID_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:id_token";
    static final String ISSUER = "https://spike.example/as";
    static final long MAX_DELEGATED_LIFETIME_SECONDS = 300;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] KEY = "spike-only-signing-key-do-not-reuse-0123".getBytes(StandardCharsets.UTF_8);

    /** Audiences registered for the agent runtime client. Anything else is refused. */
    static final Set<String> REGISTERED_AUDIENCES = Set.of("tool-search", "tool-close-case");

    record Grant(String user, String agent, Set<String> scopes, long expiresAt) {
    }

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final AtomicLong now = new AtomicLong(System.currentTimeMillis() / 1000);
    private final HttpServer server;

    MiniAuthorizationServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", this::token);
        server.start();
    }

    String tokenEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    long now() {
        return now.get();
    }

    void setNow(long epochSeconds) {
        now.set(epochSeconds);
    }

    void grant(String user, String agent, Set<String> scopes, long expiresAt) {
        grants.put(user + "|" + agent, new Grant(user, agent, scopes, expiresAt));
    }

    void revoke(String user, String agent) {
        grants.remove(user + "|" + agent);
    }

    /** Issues a signed token. Used for user sessions and agent workload identities in the tests. */
    String issue(String sub, String aud, String scope, long exp, JsonNode act) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("iss", ISSUER);
        payload.put("sub", sub);
        payload.put("aud", aud);
        payload.put("scope", scope);
        payload.put("iat", now());
        payload.put("exp", exp);
        if (act != null) {
            payload.set("act", act);
        }
        return sign(payload);
    }

    /** Verifies signature, issuer, expiry and audience. Returns the claims or throws. */
    JsonNode verify(String token, String expectedAudience) {
        JsonNode claims = verifySignatureAndTime(token);
        if (!expectedAudience.equals(claims.path("aud").asText())) {
            throw new IllegalArgumentException("audience mismatch: token aud=" + claims.path("aud").asText());
        }
        return claims;
    }

    private JsonNode verifySignatureAndTime(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !sig(parts[0] + "." + parts[1]).equals(parts[2])) {
            throw new IllegalArgumentException("bad signature");
        }
        try {
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (claims.path("exp").asLong() <= now()) {
                throw new IllegalArgumentException("expired");
            }
            return claims;
        } catch (IOException e) {
            throw new IllegalArgumentException("bad payload", e);
        }
    }

    private String sign(ObjectNode payload) {
        String header = b64(("{\"alg\":\"HS256\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String body = b64(payload.toString().getBytes(StandardCharsets.UTF_8));
        return header + "." + body + "." + sig(header + "." + body);
    }

    private static String sig(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return b64(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void token(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, error("invalid_request", "POST only"));
            return;
        }
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        try {
            respond(ex, 200, exchange(form));
        } catch (OAuthError e) {
            respond(ex, 400, error(e.code, e.getMessage()));
        }
    }

    /** RFC 8693 section 2.1 request, section 2.2 response, with the spike's delegation rules. */
    ObjectNode exchange(Map<String, String> form) {
        if (!TOKEN_EXCHANGE.equals(form.get("grant_type"))) {
            throw new OAuthError("unsupported_grant_type", "grant_type must be token-exchange");
        }
        if (!ACCESS_TOKEN_TYPE.equals(form.get("subject_token_type"))) {
            throw new OAuthError("invalid_request", "only access_token subjects are accepted");
        }
        if (form.containsKey("requested_token_type") && !ACCESS_TOKEN_TYPE.equals(form.get("requested_token_type"))) {
            throw new OAuthError("invalid_request", "only access_token can be requested");
        }
        JsonNode subject = checked(form.get("subject_token"), "subject_token");
        JsonNode actor = checked(form.get("actor_token"), "actor_token");

        String user = subject.path("sub").asText();
        String agent = actor.path("sub").asText();
        Grant grant = grants.get(user + "|" + agent);
        if (grant == null || grant.expiresAt() <= now()) {
            throw new OAuthError("invalid_grant", "no valid delegation grant: re-authorization required");
        }

        String audience = form.get("audience");
        if (audience == null || !REGISTERED_AUDIENCES.contains(audience)) {
            throw new OAuthError("invalid_target", "audience is not registered for this client");
        }

        Set<String> subjectScopes = Set.of(subject.path("scope").asText().split(" "));
        Set<String> requested = form.containsKey("scope")
                ? Set.of(form.get("scope").split(" "))
                : intersection(subjectScopes, grant.scopes());
        if (!intersection(subjectScopes, grant.scopes()).containsAll(requested)) {
            throw new OAuthError("invalid_scope", "scope may only narrow the subject token and the grant");
        }

        long exp = Math.min(now() + MAX_DELEGATED_LIFETIME_SECONDS,
                Math.min(grant.expiresAt(), subject.path("exp").asLong()));
        ObjectNode act = JSON.createObjectNode().put("sub", agent);
        if (subject.has("act")) {
            act.set("act", subject.get("act"));
        }
        String scope = String.join(" ", requested.stream().sorted().toList());
        String token = issue(user, audience, scope, exp, act);

        ObjectNode out = JSON.createObjectNode();
        out.put("access_token", token);
        out.put("issued_token_type", ACCESS_TOKEN_TYPE);
        out.put("token_type", "Bearer");
        out.put("expires_in", exp - now());
        out.put("scope", scope);
        // No refresh_token: the spike's rule is that delegated tokens are re-obtained from the grant.
        return out;
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private JsonNode checked(String token, String name) {
        if (token == null) {
            throw new OAuthError("invalid_request", name + " is required");
        }
        try {
            return verifySignatureAndTime(token);
        } catch (IllegalArgumentException e) {
            throw new OAuthError("invalid_grant", name + " is not valid: " + e.getMessage());
        }
    }

    private static ObjectNode error(String code, String description) {
        return JSON.createObjectNode().put("error", code).put("error_description", description);
    }

    private static void respond(HttpExchange ex, int status, ObjectNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    static final class OAuthError extends RuntimeException {
        final String code;

        OAuthError(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
