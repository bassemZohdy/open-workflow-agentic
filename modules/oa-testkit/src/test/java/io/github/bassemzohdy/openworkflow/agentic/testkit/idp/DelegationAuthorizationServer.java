package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-process OAuth 2.0 token endpoint for tests of the RFC 8693 client. Ported from spike D's
 * {@code MiniAuthorizationServer} (test scope on purpose: it must never be on a runtime classpath). It signs with the
 * key of a {@link FakeIdentityProvider}, so tokens verify through that issuer's JWKS like real ones.
 *
 * <p>It behaves like a standard token exchange of Keycloak 26.2 or later as far as ADR 0021 describes it, and nothing
 * more: it authenticates the client (HTTP Basic), accepts only access tokens as subject and actor, issues only for
 * registered audiences, narrows scope to the subject token's scope, lets the new token live no longer than the subject
 * token, and never issues a refresh token. It knows nothing about OUR delegation grants, exactly like Keycloak.
 * That is why the client must check the grant expiry itself, and why this server can be told to {@link Fault misbehave}:
 * the client's own checks are what the tests prove.
 *
 * <p>What the tests prove against it is the behaviour of our client and guard. It is not Keycloak, and it says nothing
 * about what Keycloak does (docs/identity.md section 9 lists the unverified part).
 */
public final class DelegationAuthorizationServer implements AutoCloseable {

    public static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    /** Ways this server can break its own contract, so that the client's defences are exercised. */
    public enum Fault {
        NONE,
        /** Adds a refresh token to the response. */
        REFRESH_TOKEN,
        /** Issues a token that lives {@link #lifetime} regardless of the subject token (set a long one). */
        IGNORE_SUBJECT_EXPIRY,
        /** Grants more scopes than were asked. */
        WIDER_SCOPE,
        /** Issues for another audience than the one asked. */
        WRONG_AUDIENCE,
        /** Issues for another subject. */
        OTHER_SUBJECT,
        /** Issues a token without an actor. */
        NO_ACTOR,
        /** Returns the subject token as the access token (passthrough). */
        ECHO_SUBJECT_TOKEN,
        /** Answers 503. */
        UNAVAILABLE,
        /** Answers 200 with a body that is not JSON. */
        NOT_JSON,
        /** Answers 200 with a body larger than the client reads. */
        HUGE_BODY,
        /** Signs iat 301 s in the past with exp 299 s ahead: a 600 s signed lifetime whose residual is under 300 s. */
        BACKDATED_IAT,
        /** Signs iat 6 s in the future (inside an ordinary 30 s verifier skew) with exp 120 s ahead. */
        FUTURE_IAT,
        /** Answers 200 with an {@code expires_in} of 3600 while the token itself is short. */
        LYING_EXPIRES_IN
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The last form the server received, for assertions about what is sent. */
    public record Received(Map<String, String> form, String authorization) {
    }

    private final FakeIdentityProvider idp;
    private final Set<String> registeredAudiences;
    private final AtomicReference<String> clientId = new AtomicReference<>();
    private final AtomicReference<String> clientSecret = new AtomicReference<>();
    private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    private final AtomicReference<Duration> lifetime = new AtomicReference<>(Duration.ofSeconds(300));
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public DelegationAuthorizationServer(FakeIdentityProvider idp, int port, String clientId, String clientSecret,
                                         Set<String> registeredAudiences) throws IOException {
        this.idp = idp;
        this.registeredAudiences = Set.copyOf(registeredAudiences);
        this.clientId.set(clientId);
        this.clientSecret.set(clientSecret);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/token", this::token);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-as");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public String tokenEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    /** The registered client's secret changes (rotation); the old one stops working at once. */
    public void rotateClientSecret(String newSecret) {
        clientSecret.set(newSecret);
    }

    public void fault(Fault value) {
        fault.set(value);
    }

    /** How long a token lives when the subject token allows it (default 300 s). */
    public void lifetime(Duration value) {
        lifetime.set(value);
    }

    public List<Received> received() {
        return Collections.unmodifiableList(new ArrayList<>(received));
    }

    public int requestCount() {
        return received.size();
    }

    // ---- minting tokens as an identity provider would (user sessions, workload identities) -------------------------

    /** A user's token, as the login of the identity provider would issue it. */
    public String userToken(String user, String tenant, String audience, Set<String> scopes, Duration lifetime)
            throws Exception {
        Map<String, Object> claims = idp.baseClaims(user, audience, lifetime);
        claims.put("scope", String.join(" ", scopes));
        claims.put("tenant", tenant);
        // the exchanging client is in the audience, as a Keycloak delegation token has it (ADR 0021 H1: inputs are
        // verified for the exchanging client)
        claims.put("aud", List.of(audience, clientId.get()));
        return idp.signClaims(claims);
    }

    /** The workload identity of an agent (client credentials): subject is the agent, kind AGENT. */
    public String workloadToken(String agent, String tenant, Duration lifetime) throws Exception {
        Map<String, Object> claims = idp.baseClaims(agent, clientId.get(), lifetime);
        claims.put("sub_kind", "AGENT");
        claims.put("tenant", tenant);
        return idp.signClaims(claims);
    }

    // ---- the endpoint ---------------------------------------------------------------------------------------------

    private void token(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, error("invalid_request"));
                return;
            }
            Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String authorization = ex.getRequestHeaders().getFirst("Authorization");
            received.add(new Received(form, authorization));
            if (!clientAuthenticated(authorization)) {
                respond(ex, 401, error("invalid_client"));
                return;
            }
            if (fault.get() == Fault.UNAVAILABLE) {
                respond(ex, 503, error("temporarily_unavailable"));
                return;
            }
            if (fault.get() == Fault.NOT_JSON) {
                respondRaw(ex, 200, "<html>not json</html>".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (fault.get() == Fault.HUGE_BODY) {
                respondRaw(ex, 200, new byte[200 * 1024]);
                return;
            }
            try {
                respond(ex, 200, exchange(form));
            } catch (OAuthError e) {
                respond(ex, 400, error(e.code));
            }
        } catch (RuntimeException e) {
            respond(ex, 500, error("server_error"));
        }
    }

    private boolean clientAuthenticated(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return false;
        }
        String decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String id = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
        String secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        return id.equals(clientId.get()) && secret.equals(clientSecret.get());
    }

    private ObjectNode exchange(Map<String, String> form) {
        if (!TOKEN_EXCHANGE.equals(form.get("grant_type"))) {
            throw new OAuthError("unsupported_grant_type");
        }
        if (!ACCESS_TOKEN_TYPE.equals(form.get("subject_token_type"))) {
            throw new OAuthError("invalid_request");
        }
        if (form.containsKey("requested_token_type") && !ACCESS_TOKEN_TYPE.equals(form.get("requested_token_type"))) {
            throw new OAuthError("invalid_request");
        }
        JsonNode subject = checked(form.get("subject_token"));
        JsonNode actor = checked(form.get("actor_token"));
        String user = subject.path("sub").asText();
        String agent = actor.path("sub").asText();

        String audience = form.get("audience");
        if (audience == null || !registeredAudiences.contains(audience)) {
            throw new OAuthError("invalid_target");
        }
        Set<String> subjectScopes = split(subject.path("scope").asText(""));
        Set<String> requested = form.containsKey("scope") ? split(form.get("scope")) : subjectScopes;
        if (!subjectScopes.containsAll(requested)) {
            throw new OAuthError("invalid_scope");
        }

        Instant now = idp.clock().instant();
        Instant subjectExpiry = Instant.ofEpochSecond(subject.path("exp").asLong());
        Instant expiry = now.plus(lifetime.get());
        if (fault.get() != Fault.IGNORE_SUBJECT_EXPIRY && subjectExpiry.isBefore(expiry)) {
            expiry = subjectExpiry;
        }

        Fault f = fault.get();
        Set<String> scopes = new LinkedHashSet<>(requested);
        if (f == Fault.WIDER_SCOPE) {
            scopes.add("admin:everything");
        }
        Map<String, Object> claims = idp.baseClaims(f == Fault.OTHER_SUBJECT ? "mallory" : user,
                f == Fault.WRONG_AUDIENCE ? "some-other-resource" : audience, Duration.ofSeconds(1));
        claims.put("exp", expiry.getEpochSecond());
        if (f == Fault.BACKDATED_IAT) {
            claims.put("iat", now.minusSeconds(301).getEpochSecond());
            claims.put("exp", now.plusSeconds(299).getEpochSecond());
        } else if (f == Fault.FUTURE_IAT) {
            claims.put("iat", now.plusSeconds(6).getEpochSecond());
            claims.put("exp", now.plusSeconds(120).getEpochSecond());
        }
        claims.put("scope", String.join(" ", scopes.stream().sorted().toList()));
        claims.put("tenant", subject.path("tenant").asText());
        if (f != Fault.NO_ACTOR) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("sub", agent);
            if (subject.has("act")) {
                act.put("act", JSON.convertValue(subject.get("act"), Map.class));
            }
            claims.put("act", act);
        }
        String access;
        try {
            access = f == Fault.ECHO_SUBJECT_TOKEN ? form.get("subject_token") : idp.signClaims(claims);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("access_token", access);
        out.put("issued_token_type", ACCESS_TOKEN_TYPE);
        out.put("token_type", "Bearer");
        out.put("expires_in", f == Fault.LYING_EXPIRES_IN ? 3600 : Math.max(1, expiry.getEpochSecond() - now.getEpochSecond()));
        out.put("scope", String.join(" ", scopes.stream().sorted().toList()));
        if (f == Fault.REFRESH_TOKEN) {
            out.put("refresh_token", "refresh-token-that-must-never-be-accepted");
        }
        return out;
    }

    private JsonNode checked(String token) {
        if (token == null) {
            throw new OAuthError("invalid_request");
        }
        try {
            JsonNode claims = idp.verify(token);
            if (claims.path("exp").asLong() <= idp.clock().instant().getEpochSecond()) {
                throw new OAuthError("invalid_grant");
            }
            return claims;
        } catch (OAuthError e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthError("invalid_grant");
        }
    }

    private static Set<String> split(String scope) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : scope.split(" ")) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static ObjectNode error(String code) {
        return JSON.createObjectNode().put("error", code);
    }

    private static void respond(HttpExchange ex, int status, ObjectNode body) throws IOException {
        respondRaw(ex, status, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void respondRaw(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
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

    private static final class OAuthError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        OAuthError(String code) {
            super(code);
            this.code = code;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
