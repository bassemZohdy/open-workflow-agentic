package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The RFC 8693 client. It turns a delegation grant into one short-lived token for one audience.
 *
 * <p>What this class enforces itself, because the authorization server cannot know it (Keycloak 26.2 does not know our
 * grant expiry; ADR 0021 section 1):
 * <ol>
 *   <li>Before any request: the grant is active now (the whole chain), the scopes asked are held by the grant.
 *       Otherwise nothing is sent.</li>
 *   <li>On the answer: no {@code refresh_token}; token type Bearer; the token verifies (signature, issuer, time) for the
 *       audience asked and for no other; it names the grant's user as subject and the grant's agent as nearest actor and
 *       the grant's tenant; its scopes are a subset of the scopes asked; it lives at most 300 seconds and not past the
 *       grant's expiry; {@code expires_in}, when present, agrees; it is not the subject or actor token handed back.
 *       Any failure discards the token.</li>
 *   <li>An audit event for the issue is written before the token is returned; if it cannot be written the token is
 *       discarded. Refusals are audited best effort.</li>
 * </ol>
 *
 * <p>The client authenticates with a secret read from the {@link SecretProvider} on every call, so a rotated secret is
 * used on the next exchange. The endpoint must be https, or http on loopback (tests). Redirects are not followed.
 * The response is read up to 64 KiB.
 *
 * <p>Verified against the in-process authorization server of oa-testkit only. Against Keycloak: unverified; see
 * docs/identity.md section 9.
 */
public final class Rfc8693TokenExchanger implements TokenExchanger {

    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    public static final String ISSUED = "identity.token.exchanged";
    public static final String REFUSED = "identity.token.exchange.refused";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final String clientId;
    private final String clientSecretName;
    private final SecretProvider secrets;
    private final DelegationGrants grants;
    private final TokenVerifier verifier;
    private final IdentityAudit audit;
    private final Clock clock;
    private final Duration timeout;
    private final HttpClient http;

    public Rfc8693TokenExchanger(String tokenEndpoint, String clientId, String clientSecretName, SecretProvider secrets,
                                 DelegationGrants grants, TokenVerifier verifier, IdentityAudit audit, Clock clock,
                                 Duration timeout) {
        this.endpoint = checkedEndpoint(tokenEndpoint);
        this.clientId = requireText(clientId, "clientId");
        this.clientSecretName = requireText(clientSecretName, "clientSecretName");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public IssuedToken exchange(ExchangeRequest request) {
        Objects.requireNonNull(request, "request");
        DelegationGrant grant = activeGrant(request);
        try {
            IssuedToken token = requestToken(request, grant);
            try {
                audit.record(grant.agent(), ISSUED, IdentityAudit.TOKEN, grant.id().toString(), grant.tenant(), null,
                        issuedAttributes(request, token));
            } catch (RuntimeException e) {
                throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                        "the token was discarded because its audit event could not be written", e);
            }
            return token;
        } catch (ExchangeException e) {
            auditRefusal(grant, request, e);
            throw e;
        }
    }

    // ---- before the request -------------------------------------------------------------------------------------

    private DelegationGrant activeGrant(ExchangeRequest request) {
        GrantCheck check;
        try {
            check = grants.check(request.grant().id());
        } catch (RuntimeException e) {
            throw new ExchangeException(ExchangeException.Code.UNAVAILABLE, "the grant could not be checked", e);
        }
        if (check instanceof GrantCheck.Inactive inactive) {
            throw new ExchangeException(inactive.reason());
        }
        DelegationGrant grant = ((GrantCheck.Active) check).grant();
        if (!grant.covers(request.scopes())) {
            throw new ExchangeException(ExchangeException.Code.SCOPE_WIDENING,
                    "the scopes asked for are not all held by the grant");
        }
        return grant;
    }

    // ---- the request --------------------------------------------------------------------------------------------

    private IssuedToken requestToken(ExchangeRequest request, DelegationGrant grant) {
        Credential secret;
        try {
            secret = secrets.current(clientSecretName);
        } catch (SecretUnavailableException e) {
            throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                    "the client secret '" + clientSecretName + "' is unavailable", e);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", GRANT_TYPE);
        form.put("subject_token", request.subjectToken().reveal());
        form.put("subject_token_type", ACCESS_TOKEN_TYPE);
        form.put("actor_token", request.actorToken().reveal());
        form.put("actor_token_type", ACCESS_TOKEN_TYPE);
        form.put("requested_token_type", ACCESS_TOKEN_TYPE);
        form.put("audience", request.audience());
        form.put("scope", String.join(" ", new TreeSet<>(request.scopes())));
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (formEncode(clientId) + ":" + formEncode(secret.reveal())).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        int status;
        byte[] body;
        try {
            HttpResponse<InputStream> response = this.http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeException(ExchangeException.Code.UNAVAILABLE, "the token request was interrupted", e);
        } catch (IOException | RuntimeException e) {
            throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                    "the authorization server could not be reached", e);
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new ExchangeException(ExchangeException.Code.BAD_RESPONSE, "the response is too large");
        }
        if (status == 200) {
            return accept(parse(body), request, grant);
        }
        if (status == 400 || status == 401 || status == 403) {
            throw new ExchangeException(ExchangeException.Code.REFUSED,
                    "the authorization server refused the exchange (HTTP " + status + ")", oauthError(body));
        }
        throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                "the authorization server answered HTTP " + status);
    }

    private static JsonNode parse(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new ExchangeException(ExchangeException.Code.BAD_RESPONSE, "the response is not a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.Code.BAD_RESPONSE, "the response is not JSON");
        }
    }

    /** The {@code error} member when it is a plain token such as {@code invalid_scope}; otherwise null. Never free text. */
    private static String oauthError(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            String error = node == null ? null : node.path("error").asText(null);
            return error != null && error.matches("[a-z_]{1,64}") ? error : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- the answer ---------------------------------------------------------------------------------------------

    private IssuedToken accept(JsonNode response, ExchangeRequest request, DelegationGrant grant) {
        if (response.has("refresh_token")) {
            throw bad("the server issued a refresh token");
        }
        String type = response.path("token_type").asText("");
        if (!"bearer".equalsIgnoreCase(type)) {
            throw bad("the token type is not Bearer");
        }
        String issuedType = response.path("issued_token_type").asText(ACCESS_TOKEN_TYPE);
        if (!ACCESS_TOKEN_TYPE.equals(issuedType)) {
            throw bad("the issued token type is not an access token");
        }
        String text = response.path("access_token").asText("");
        if (text.isEmpty()) {
            throw bad("the response has no access token");
        }
        if (text.equals(request.subjectToken().reveal()) || text.equals(request.actorToken().reveal())) {
            throw bad("the server returned the token it was given");
        }
        VerifiedToken verified;
        try {
            verified = verifier.verify(text, request.audience());
        } catch (TokenRejectedException e) {
            throw new ExchangeException(e.reason() == TokenRejectedException.Reason.VERIFIER_UNAVAILABLE
                    ? ExchangeException.Code.UNAVAILABLE : ExchangeException.Code.BAD_RESPONSE,
                    "the issued token does not verify: " + e.reason(), e);
        }
        if (!verified.audiences().equals(Set.of(request.audience()))) {
            throw bad("the issued token is not for exactly the audience asked");
        }
        if (!verified.subject().equals(grant.delegator())) {
            throw bad("the issued token is for another subject than the grant's user");
        }
        if (!grant.tenant().equals(verified.tenant())) {
            throw bad("the issued token is for another tenant than the grant's");
        }
        if (!verified.isDelegated() || !grant.agent().id().equals(verified.actor())) {
            throw bad("the issued token does not name the grant's agent as its actor");
        }
        if (!request.scopes().containsAll(verified.scopes()) || verified.scopes().isEmpty()) {
            throw bad("the issued token has scopes that were not asked for, or none");
        }
        Instant now = clock.instant();
        Instant expires = verified.expiresAt();
        if (!expires.isAfter(now)) {
            throw bad("the issued token is already expired");
        }
        if (expires.isAfter(now.plusSeconds(MAX_LIFETIME_SECONDS))) {
            throw bad("the issued token lives longer than " + MAX_LIFETIME_SECONDS + " seconds");
        }
        if (expires.isAfter(grant.expiresAt())) {
            throw bad("the issued token outlives the delegation grant");
        }
        JsonNode expiresIn = response.get("expires_in");
        if (expiresIn != null && (!expiresIn.canConvertToLong() || expiresIn.asLong() > MAX_LIFETIME_SECONDS
                || expiresIn.asLong() <= 0)) {
            throw bad("expires_in is missing a valid value or longer than " + MAX_LIFETIME_SECONDS + " seconds");
        }
        return new IssuedToken(Credential.of(text), request.audience(), verified.scopes(), expires,
                verified.tokenId(), verified.actors(), grant.id());
    }

    private static ExchangeException bad(String message) {
        return new ExchangeException(ExchangeException.Code.BAD_RESPONSE, message);
    }

    // ---- audit --------------------------------------------------------------------------------------------------

    private Map<String, String> issuedAttributes(ExchangeRequest request, IssuedToken token) {
        Map<String, String> attributes = new LinkedHashMap<>(IdentityAudit.scopeAttributes(token.scopes()));
        attributes.put("audience", token.audience());
        attributes.put("grantId", token.grantId().toString());
        attributes.put("expiresAt", token.expiresAt().toString());
        if (token.tokenId() != null) {
            attributes.put("tokenId", token.tokenId());
        }
        return attributes;
    }

    private void auditRefusal(DelegationGrant grant, ExchangeRequest request, ExchangeException e) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("audience", request.audience());
            attributes.put("grantId", grant.id().toString());
            attributes.put("code", e.code().name());
            if (e.oauthError() != null) {
                attributes.put("oauthError", e.oauthError());
            }
            audit.record(grant.agent(), REFUSED, IdentityAudit.TOKEN, grant.id().toString(), grant.tenant(), null,
                    attributes);
        } catch (RuntimeException ignored) {
            // a refusal stands whether or not it could be written down
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static URI checkedEndpoint(String value) {
        URI uri = URI.create(requireText(value, "tokenEndpoint"));
        String host = uri.getHost();
        boolean loopback = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("the token endpoint must use https (http only for a loopback host)");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("the token endpoint must not carry credentials");
        }
        return uri;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String encode(Map<String, String> form) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(formEncode(entry.getKey())).append('=').append(formEncode(entry.getValue()));
        }
        return out.toString();
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
