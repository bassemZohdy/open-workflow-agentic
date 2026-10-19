package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider.TokenSpec;
import io.github.bassemzohdy.openworkflow.agentic.testkit.time.ControllableClock;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakeIdentityProviderTest {

    private ControllableClock clock;
    private FakeIdentityProvider idp;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        clock = new ControllableClock(Instant.now().minusSeconds(60));
        idp = new FakeIdentityProvider(clock).start();
    }

    @AfterEach
    void stop() {
        idp.close();
    }

    @Test
    void tokenVerifiesWithTheJwksPublishedByTheProvider() throws Exception {
        String token = idp.issue(TokenSpec.of("bassem", Duration.ofMinutes(10), "cases:read", "cases:write"));

        JWKSet published = JWKSet.load(new URL(idp.jwksUri()));
        RSAKey key = (RSAKey) published.getKeyByKeyId(FakeIdentityProvider.KEY_ID);
        SignedJWT jwt = SignedJWT.parse(token);
        assertTrue(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())), "signature must verify with the JWKS key");

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals("bassem", claims.getSubject());
        assertEquals(idp.issuer(), claims.getIssuer());
        assertEquals("cases:read cases:write", claims.getStringClaim("scope"));
        assertEquals(clock.instant().plus(Duration.ofMinutes(10)).getEpochSecond(), claims.getExpirationTime().toInstant().getEpochSecond());
    }

    @Test
    void tokenPastItsExpiryOnTheInjectedClockIsRejectedByTheCaller() throws Exception {
        String token = idp.issue(TokenSpec.of("bassem", Duration.ofMinutes(5), "cases:read"));
        clock.advance(Duration.ofMinutes(6));
        Date expiry = SignedJWT.parse(token).getJWTClaimsSet().getExpirationTime();
        assertFalse(Date.from(clock.instant()).before(expiry), "the token must be expired at the injected time");
    }

    @Test
    void tamperedPayloadDoesNotVerify() throws Exception {
        String token = idp.issue(TokenSpec.of("bassem", Duration.ofMinutes(5), "cases:read"));
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"mallory\",\"scope\":\"admin\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThrows(Exception.class, () -> idp.verify(forged));
    }

    @Test
    void tokenExchangeNarrowsScopeAndCapsLifetimeAtTheSubjectToken() throws Exception {
        String subject = idp.issue(TokenSpec.of("agent-42", Duration.ofMinutes(3), "cases:read", "cases:write"));
        HttpResponse<String> response = exchange(subject, "cases:read");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertEquals("cases:read", body.path("scope").asText());
        assertEquals("Bearer", body.path("token_type").asText());
        assertEquals(FakeIdentityProvider.ACCESS_TOKEN_TYPE, body.path("issued_token_type").asText());
        assertTrue(body.path("expires_in").asLong() <= 180, "must not outlive the subject token: " + body);
        JsonNode narrowed = idp.verify(body.path("access_token").asText());
        assertEquals("agent-42", narrowed.path("sub").asText());
        assertEquals("cases:read", narrowed.path("scope").asText());
    }

    @Test
    void tokenExchangeWithoutAScopeKeepsTheSubjectScopes() throws Exception {
        String subject = idp.issue(TokenSpec.of("agent-42", Duration.ofMinutes(30), "a", "b"));
        HttpResponse<String> response = exchange(subject, null);
        assertEquals(200, response.statusCode());
        assertEquals("a b", new ObjectMapper().readTree(response.body()).path("scope").asText());
    }

    @Test
    void scopeWideningIsRefused() throws Exception {
        String subject = idp.issue(TokenSpec.of("agent-42", Duration.ofMinutes(5), "cases:read"));
        HttpResponse<String> response = exchange(subject, "cases:read cases:admin");
        assertEquals(400, response.statusCode());
        assertEquals("invalid_scope", new ObjectMapper().readTree(response.body()).path("error").asText());
    }

    @Test
    void anExpiredSubjectTokenIsRefused() throws Exception {
        String subject = idp.issue(TokenSpec.of("agent-42", Duration.ofMinutes(5), "cases:read"));
        clock.advance(Duration.ofMinutes(5));
        HttpResponse<String> response = exchange(subject, "cases:read");
        assertEquals(400, response.statusCode());
        assertEquals("invalid_grant", new ObjectMapper().readTree(response.body()).path("error").asText());
    }

    @Test
    void aTamperedSubjectTokenIsRefused() throws Exception {
        String subject = idp.issue(TokenSpec.of("agent-42", Duration.ofMinutes(5), "cases:read"));
        HttpResponse<String> response = exchange(subject.substring(0, subject.length() - 4) + "AAAA", "cases:read");
        assertEquals(400, response.statusCode());
    }

    @Test
    void anUnsupportedGrantIsRefused() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(idp.tokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals("unsupported_grant_type", new ObjectMapper().readTree(response.body()).path("error").asText());
    }

    @Test
    void scopeListHelperSplitsOnWhitespace() {
        assertEquals(List.of("a", "b", "c"), FakeIdentityProvider.scopeList("  a   b c "));
        assertEquals(Set.of("cases:read"), Set.copyOf(FakeIdentityProvider.scopeList("cases:read")));
    }

    private HttpResponse<String> exchange(String subjectToken, String scope) throws Exception {
        String form = "grant_type=" + enc(FakeIdentityProvider.TOKEN_EXCHANGE_GRANT)
                + "&subject_token=" + enc(subjectToken)
                + "&subject_token_type=" + enc(FakeIdentityProvider.ACCESS_TOKEN_TYPE)
                + (scope == null ? "" : "&scope=" + enc(scope));
        return http.send(HttpRequest.newBuilder(URI.create(idp.tokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void assertThrows(Class<? extends Throwable> type, org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertThrows(type, e);
    }
}
