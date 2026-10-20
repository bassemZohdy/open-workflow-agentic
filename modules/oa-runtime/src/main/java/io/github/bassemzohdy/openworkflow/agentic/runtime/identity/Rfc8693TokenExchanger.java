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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The RFC 8693 client. It turns a delegation grant into one short-lived token for one audience.
 *
 * <p>What this class enforces itself, because the authorization server cannot know it (the server does not know our
 * grants; ADR 0021 section 1 and the H1 amendment):
 * <ol>
 *   <li>Before any request: the grant is active now (the whole chain), the scopes asked are held by the grant, and
 *       both input tokens verify (signature, issuer, time, and the exchanging client as audience). The subject token
 *       must be the grant's user in the grant's tenant, hold every scope asked for, and carry exactly the delegation chain
 *       of the grant's parent (none for a root grant); the actor token must be the grant's own workload token. Otherwise nothing is sent.</li>
 *   <li>On the answer: no {@code refresh_token}; token type Bearer; the token verifies (signature, issuer, time) for the
 *       audience asked and for no other; it has a {@code jti} and an issue time; it names the grant's user as subject,
 *       the grant's tenant and exactly the grant's chain of actors, nearest first (a missing or different chain is refused,
 *       never flattened); its
 *       scopes are a subset of the scopes asked; the signed expiry is at most 300 seconds away and not past the verified
 *       subject token's expiry or the grant's expiry; {@code expires_in}, when present, agrees; it is not the subject or
 *       actor token handed back. An overlong bearer is refused: only the signed {@code exp} counts, never a shortened
 *       Java-side expiry. Any failure discards the token.</li>
 *   <li>The grant is checked again after the answer, so a grant revoked while the request was in flight issues nothing.</li>
 *   <li>An audit event for the issue is written before the token is returned; if it cannot be written the token is
 *       discarded. Refusals are audited best effort.</li>
 * </ol>
 *
 * <p>The client authenticates with a secret read from the {@link SecretProvider} on every call, so a rotated secret is
 * used on the next exchange. The endpoint must be https, or http on loopback (tests). Redirects are not followed.
 * The response is read up to 64 KiB.
 *
 * <p>Verified against the in-process authorization server of oa-testkit and against real Keycloak 26.2.5 (standard
 * exchange, refused: no {@code act}) and 26.8.1 (native delegation preview); see docs/identity.md section 9.
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
    private final IssuedTokenBindings bindings;
    private final TokenVerifier verifier;
    private final IdentityAudit audit;
    private final Clock clock;
    private final Duration timeout;
    private final HttpClient http;

    public Rfc8693TokenExchanger(String tokenEndpoint, String clientId, String clientSecretName, SecretProvider secrets,
                                 DelegationGrants grants, IssuedTokenBindings bindings, TokenVerifier verifier,
                                 IdentityAudit audit, Clock clock, Duration timeout) {
        this.endpoint = checkedEndpoint(tokenEndpoint);
        this.clientId = requireText(clientId, "clientId");
        this.clientSecretName = requireText(clientSecretName, "clientSecretName");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
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
            Inputs inputs = verifyInputs(request, grant);
            IssuedToken token = requestToken(request, grant, inputs);
            recheckGrant(request, grant);
            bind(token, grant);
            try {
                audit.record(grant.agent(), ISSUED, IdentityAudit.TOKEN, grant.id().toString(), grant.tenant(), null,
                        issuedAttributes(request, token));
            } catch (RuntimeException e) {
                throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                        "the token was discarded because its audit event could not be written", e);
            }
            releaseCheck(request, grant, token, inputs);
            return token;
        } catch (ExchangeException e) {
            auditRefusal(grant, request, e);
            throw e;
        }
    }

    /** What the verified inputs contribute: the subject bounds the credential, the actor must be fresh (ADR 0021 H3). */
    private record Inputs(Instant subjectExpiry, Instant actorIssuedAt, Instant actorExpiresAt) { }

    /** The actor window of ADR 0021 H3: fixed upper bounds, not operator settings. */
    static final java.time.Duration ACTOR_MAX_AGE = java.time.Duration.ofSeconds(60);
    static final java.time.Duration ACTOR_FUTURE_ALLOWANCE = java.time.Duration.ofSeconds(5);

    /** A missing, stale, future or expired actor proof fails closed: {@code now-60s <= iat <= now+5s} and {@code exp > now}. */
    private void requireFreshActor(Inputs inputs, Instant now) {
        if (inputs.actorIssuedAt() == null) {
            throw input("the actor token has no issue time");
        }
        if (inputs.actorIssuedAt().isBefore(now.minus(ACTOR_MAX_AGE))) {
            throw input("the actor token is stale: the workload must authenticate within the last 60 seconds");
        }
        if (inputs.actorIssuedAt().isAfter(now.plus(ACTOR_FUTURE_ALLOWANCE))) {
            throw input("the actor token was issued in the future");
        }
        if (!inputs.actorExpiresAt().isAfter(now)) {
            throw input("the actor token is expired");
        }
    }

    /**
     * The last look before the bearer leaves. The grant is read first because that is the last blocking call; the pure time
     * checks follow it, so release time is the enforced time: the signed token must still be inside its lifetime (it was
     * bounded by the subject and the grant chain when requested) and the actor must still be fresh. A token refused here
     * is bound but never returned.
     */
    private void releaseCheck(ExchangeRequest request, DelegationGrant grant, IssuedToken token, Inputs inputs) {
        recheckGrant(request, grant);
        Instant now = clock.instant();
        if (!token.expiresAt().isAfter(now)) {
            throw new ExchangeException(ExchangeException.Code.BAD_RESPONSE,
                    "the issued token expired before it could be released; it was discarded");
        }
        requireFreshActor(inputs, now);
    }

    /**
     * Records which grant issued the token before it is returned (ADR 0021 H1): a resource admits the token only through
     * this binding, so the grant that issued it, and its ancestors, decide whether it is still valid. No binding, no token.
     */
    private void bind(IssuedToken token, DelegationGrant grant) {
        try {
            bindings.bind(token.issuer(), token.tokenId(),
                    new IssuedTokenBindings.Binding(grant.id(), grant.tenant(), token.expiresAt()));
        } catch (RuntimeException e) {
            throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                    "the token was discarded because its grant binding could not be stored", e);
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

    /**
     * Verifies both input tokens before their identity or expiry is used (ADR 0021 H1, item 1). Returns the verified
     * expiry of the subject token, which bounds the lifetime of the token that may be issued.
     */
    private Inputs verifyInputs(ExchangeRequest request, DelegationGrant grant) {
        Instant now = clock.instant();
        VerifiedToken subject = verifyInput(request.subjectToken(), "subject");
        List<String> expected = expectedActors(grant);
        if (!subject.actors().equals(expected.subList(1, expected.size()))) {
            throw input("the subject token's delegation chain is not the chain of the grant's parent");
        }
        if (!subject.subject().equals(grant.delegator())) {
            throw input("the subject token is not the grant's user");
        }
        if (!grant.tenant().equals(subject.tenant())) {
            throw input("the subject token is for another tenant than the grant's");
        }
        if (!subject.expiresAt().isAfter(now)) {
            throw input("the subject token is expired");
        }
        // Keycloak's native delegation takes the scopes of the new token from the actor client's configuration, not
        // from the subject token (observed on 26.8.1), so the user's own scopes are the bound only if we enforce it
        if (!subject.scopes().containsAll(request.scopes())) {
            throw new ExchangeException(ExchangeException.Code.SCOPE_WIDENING,
                    "the scopes asked for are not all held by the subject token");
        }
        VerifiedToken actor = verifyInput(request.actorToken(), "actor");
        if (actor.isDelegated()) {
            throw input("the actor token is not the agent's own workload token");
        }
        if (!actor.subject().equals(grant.agent())) {
            throw input("the actor token is not the grant's agent");
        }
        // The actor authenticates the workload at exchange (fresh), the subject and the grant chain bound the credential;
        // the credential need not expire with the workload proof (ADR 0021 H3 freshness amendment).
        Inputs inputs = new Inputs(subject.expiresAt(), actor.issuedAt(), actor.expiresAt());
        requireFreshActor(inputs, now);
        return inputs;
    }

    private VerifiedToken verifyInput(Credential token, String role) {
        try {
            return verifier.verify(token.reveal(), clientId);
        } catch (TokenRejectedException e) {
            if (e.reason() == TokenRejectedException.Reason.VERIFIER_UNAVAILABLE) {
                throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                        "the " + role + " token could not be verified", e);
            }
            throw new ExchangeException(ExchangeException.Code.INPUT_REJECTED,
                    "the " + role + " token does not verify: " + e.reason(), e);
        }
    }

    /** The actors an issued token must carry: the agents of the grant's chain, nearest (the grant's own agent) first. */
    private static List<String> expectedActors(DelegationGrant grant) {
        List<String> actors = new ArrayList<>(grant.chain());
        Collections.reverse(actors);
        return actors;
    }

    private static ExchangeException input(String message) {
        return new ExchangeException(ExchangeException.Code.INPUT_REJECTED, message);
    }

    /** The grant is checked again after the answer: a grant revoked meanwhile must not yield a usable token. */
    private void recheckGrant(ExchangeRequest request, DelegationGrant grant) {
        GrantCheck check;
        try {
            check = grants.check(grant.id());
        } catch (RuntimeException e) {
            throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                    "the grant could not be checked after the exchange; the token was discarded", e);
        }
        if (check instanceof GrantCheck.Inactive inactive) {
            throw new ExchangeException(inactive.reason());
        }
        if (!((GrantCheck.Active) check).grant().covers(request.scopes())) {
            throw new ExchangeException(ExchangeException.Code.SCOPE_WIDENING,
                    "the scopes asked for are no longer all held by the grant");
        }
    }

    // ---- the request --------------------------------------------------------------------------------------------

    private IssuedToken requestToken(ExchangeRequest request, DelegationGrant grant, Inputs inputs) {
        Credential secret;
        try {
            secret = secrets.current(clientSecretName);
        } catch (SecretUnavailableException e) {
            throw new ExchangeException(ExchangeException.Code.UNAVAILABLE,
                    "the client secret '" + clientSecretName + "' is unavailable", e);
        }
        // Verification and the secret lookup can block: the inputs are judged at the moment of sending, not when the
        // exchange began. A request is never sent for a stale actor or an expired subject.
        Instant sending = clock.instant();
        requireFreshActor(inputs, sending);
        if (!inputs.subjectExpiry().isAfter(sending)) {
            throw input("the subject token is expired");
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
            return accept(parse(body), request, grant, inputs.subjectExpiry());
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

    private IssuedToken accept(JsonNode response, ExchangeRequest request, DelegationGrant grant,
                               Instant subjectExpiry) {
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
        if (!verified.actors().equals(expectedActors(grant))) {
            throw bad("the issued token's chain of actors is not exactly the grant's chain; a chain is never flattened");
        }
        if (verified.tokenId() == null || verified.tokenId().isBlank() || verified.issuedAt() == null) {
            throw bad("the issued token has no jti or no issue time");
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
        // the signed claims themselves, independent of the verifier's skew: positive exact lifetime of at most 300 s and
        // an issue time at most 5 s ahead (ADR 0021 H3, signed response contract)
        java.time.Duration lifetime = java.time.Duration.between(verified.issuedAt(), expires);
        if (lifetime.isNegative() || lifetime.isZero()
                || lifetime.compareTo(java.time.Duration.ofSeconds(MAX_LIFETIME_SECONDS)) > 0) {
            throw bad("the issued token's signed lifetime is not positive and at most " + MAX_LIFETIME_SECONDS + " seconds");
        }
        if (verified.issuedAt().isAfter(now.plus(ACTOR_FUTURE_ALLOWANCE))) {
            throw bad("the issued token was issued in the future");
        }
        if (expires.isAfter(grant.expiresAt())) {
            throw bad("the issued token outlives the delegation grant");
        }
        if (expires.isAfter(subjectExpiry)) {
            throw bad("the issued token outlives the verified subject token; a near-expiry subject needs re-authorization");
        }
        JsonNode expiresIn = response.get("expires_in");
        if (expiresIn != null && (!expiresIn.canConvertToLong() || expiresIn.asLong() > MAX_LIFETIME_SECONDS
                || expiresIn.asLong() <= 0)) {
            throw bad("expires_in is missing a valid value or longer than " + MAX_LIFETIME_SECONDS + " seconds");
        }
        return new IssuedToken(Credential.of(text), request.audience(), verified.scopes(), expires,
                verified.issuer(), verified.tokenId(), verified.actors(), grant.id());
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
