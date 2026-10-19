package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A minimal OpenID Connect issuer for isolated tests, in-process, with no framework.
 *
 * <ul>
 *   <li>{@link #issue(TokenSpec)} signs an RS256 access token with a fresh 2048-bit key. Subject, scopes, audience
 *       and lifetime are configurable; {@code iat} and {@code exp} come from the injected {@link Clock}.</li>
 *   <li>{@code GET /jwks.json} publishes the public key as a JWKS.</li>
 *   <li>{@code POST /token} implements the RFC 8693 token exchange. The subject token must be valid for the clock.
 *       The new token narrows scope: requested scopes must be a subset of the subject token's scopes, otherwise the
 *       exchange fails with {@code invalid_scope}. Its lifetime never extends past the subject token's expiry.</li>
 * </ul>
 *
 * <p>Verification of tokens is left to a separate library in tests, so the issuer is not checked against itself.
 * Not a security product: keys are generated per instance and nothing is persisted.
 */
public final class FakeIdentityProvider implements AutoCloseable {

    public static final String TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    public static final String KEY_ID = "testkit-rsa-1";
    static final Duration EXCHANGED_LIFETIME = Duration.ofMinutes(5);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** What to put in a token. {@code scopes} is a set; the token carries it space-separated. */
    public record TokenSpec(String subject, Set<String> scopes, Duration lifetime, String audience) {
        public TokenSpec {
            scopes = new LinkedHashSet<>(scopes);
            if (lifetime.isNegative() || lifetime.isZero()) {
                throw new IllegalArgumentException("lifetime must be positive");
            }
        }

        public static TokenSpec of(String subject, Duration lifetime, String... scopes) {
            return new TokenSpec(subject, new LinkedHashSet<>(Arrays.asList(scopes)), lifetime, null);
        }
    }

    private final Clock clock;
    private final KeyPair keys;
    private HttpServer http;
    private String issuer;

    public FakeIdentityProvider(Clock clock) throws Exception {
        this.clock = clock;
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        this.keys = gen.generateKeyPair();
    }

    /** Starts the HTTP endpoints on 127.0.0.1 with a free port. */
    public synchronized FakeIdentityProvider start() throws IOException {
        return start(0);
    }

    /** Starts the HTTP endpoints on 127.0.0.1 on {@code port} (0 chooses a free one). */
    public synchronized FakeIdentityProvider start(int port) throws IOException {
        if (http != null) {
            throw new IllegalStateException("already started");
        }
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/jwks.json", this::handleJwks);
        http.createContext("/token", this::handleToken);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-idp");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        issuer = "http://127.0.0.1:" + http.getAddress().getPort();
        return this;
    }

    /** The issuer URL, which is also the {@code iss} claim of every token. */
    public String issuer() {
        requireStarted();
        return issuer;
    }

    public String jwksUri() {
        return issuer() + "/jwks.json";
    }

    public String tokenUri() {
        return issuer() + "/token";
    }

    /** Signs an access token for {@code spec}. Works without the HTTP server. */
    public String issue(TokenSpec spec) throws Exception {
        Instant now = clock.instant();
        ObjectNode claims = JSON.createObjectNode();
        claims.put("iss", issuerOrPlaceholder());
        claims.put("sub", spec.subject());
        if (spec.audience() != null) {
            claims.put("aud", spec.audience());
        }
        claims.put("scope", String.join(" ", spec.scopes()));
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(spec.lifetime()).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        return sign(claims);
    }

    /**
     * Claims of a token as {@link #issue} would write them, as a mutable map: {@code iss}, {@code sub}, {@code aud}
     * (when not null), {@code iat}, {@code exp} (from the clock and {@code lifetime}) and {@code jti}. A test changes
     * or removes entries (a missing {@code exp}, an {@code act} chain, an array audience, a tenant) and signs the
     * result with {@link #signClaims}.
     */
    public Map<String, Object> baseClaims(String subject, String audience, Duration lifetime) {
        Instant now = clock.instant();
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("iss", issuerOrPlaceholder());
        claims.put("sub", subject);
        if (audience != null) {
            claims.put("aud", audience);
        }
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(lifetime).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        return claims;
    }

    /** Signs exactly these claims with this issuer's key (RS256, key id {@link #KEY_ID}). Nothing is added or checked. */
    public String signClaims(Map<String, Object> claims) throws Exception {
        return sign(JSON.valueToTree(claims));
    }

    /** The clock this issuer reads for {@code iat} and {@code exp}. */
    public Clock clock() {
        return clock;
    }

    /** Checks the signature with this issuer's key and returns the claims, or throws if it does not verify. */
    public JsonNode verify(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a compact JWS");
        }
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(keys.getPublic());
        s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!s.verify(B64D.decode(parts[2]))) {
            throw new IllegalArgumentException("signature does not verify");
        }
        return JSON.readTree(B64D.decode(parts[1]));
    }

    /** The JWKS document with the one public key. */
    public String jwks() throws IOException {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        ObjectNode key = JSON.createObjectNode();
        key.put("kty", "RSA");
        key.put("kid", KEY_ID);
        key.put("alg", "RS256");
        key.put("use", "sig");
        key.put("n", unsigned(pub.getModulus()));
        key.put("e", unsigned(pub.getPublicExponent()));
        ObjectNode doc = JSON.createObjectNode();
        ArrayNode keyList = doc.putArray("keys");
        keyList.add(key);
        return JSON.writeValueAsString(doc);
    }

    public PublicKey publicKey() {
        return keys.getPublic();
    }

    @Override
    public synchronized void close() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
    }

    private String issuerOrPlaceholder() {
        return issuer != null ? issuer : "urn:testkit:unstarted";
    }

    private void requireStarted() {
        if (http == null) {
            throw new IllegalStateException("start() the identity provider first");
        }
    }

    private String sign(ObjectNode claims) throws Exception {
        ObjectNode header = JSON.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KEY_ID);
        String signingInput = B64.encodeToString(JSON.writeValueAsBytes(header)) + "."
                + B64.encodeToString(JSON.writeValueAsBytes(claims));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(keys.getPrivate());
        s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64.encodeToString(s.sign());
    }

    private void handleJwks(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        try {
            send(ex, 200, jwks());
        } catch (IOException e) {
            send(ex, 500, "{\"error\":\"server_error\"}");
        }
    }

    private void handleToken(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!TOKEN_EXCHANGE_GRANT.equals(form.get("grant_type"))) {
            send(ex, 400, error("unsupported_grant_type"));
            return;
        }
        String subjectToken = form.get("subject_token");
        if (subjectToken == null) {
            send(ex, 400, error("invalid_request"));
            return;
        }
        try {
            JsonNode subject = verify(subjectToken);
            Instant now = clock.instant();
            Instant subjectExp = Instant.ofEpochSecond(subject.path("exp").asLong());
            if (!now.isBefore(subjectExp)) {
                send(ex, 400, error("invalid_grant"));
                return;
            }
            Set<String> subjectScopes = scopesOf(subject.path("scope").asText(""));
            String requested = form.getOrDefault("scope", "");
            Set<String> granted = requested.isBlank() ? subjectScopes : scopesOf(requested);
            if (!subjectScopes.containsAll(granted)) {
                send(ex, 400, error("invalid_scope"));
                return;
            }
            Instant exp = now.plus(EXCHANGED_LIFETIME);
            if (exp.isAfter(subjectExp)) {
                exp = subjectExp;
            }
            ObjectNode claims = JSON.createObjectNode();
            claims.put("iss", issuer);
            claims.put("sub", subject.path("sub").asText());
            claims.put("scope", String.join(" ", granted));
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", exp.getEpochSecond());
            claims.put("jti", UUID.randomUUID().toString());
            String token = sign(claims);
            ObjectNode body = JSON.createObjectNode();
            body.put("access_token", token);
            body.put("issued_token_type", ACCESS_TOKEN_TYPE);
            body.put("token_type", "Bearer");
            body.put("expires_in", exp.getEpochSecond() - now.getEpochSecond());
            body.put("scope", String.join(" ", granted));
            send(ex, 200, JSON.writeValueAsString(body));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            send(ex, 400, error("invalid_grant"));
        } catch (Exception e) {
            send(ex, 500, error("server_error"));
        }
    }

    private static Set<String> scopesOf(String scope) {
        return Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String error(String code) throws IOException {
        return JSON.writeValueAsString(Map.of("error", code));
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Unsigned big-endian bytes, base64url encoded, as JWK requires. */
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0 && bytes.length > 1) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return B64.encodeToString(bytes);
    }

    /** Scopes as a list, for assertions in tests. */
    public static List<String> scopeList(String scope) {
        return List.copyOf(scopesOf(scope));
    }
}
