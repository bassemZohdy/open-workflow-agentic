package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JwtTokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.testkit.time.ControllableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JwtTokenVerifier} against the testkit's fake issuer, over HTTP (the JWKS is fetched from the fake). This proves
 * the runtime's verification logic and its fail-closed behaviour. It does not prove anything about Keycloak's tokens:
 * the claim layout here (string {@code scope}, {@code aud}, a {@code tenant} claim) is what the fake writes and what
 * docs/identity.md says Keycloak must be configured to write.
 */
class JwtTokenVerifierTest {

    private static final String AUDIENCE = "oa-api";

    private ControllableClock clock;
    private FakeIdentityProvider idp;
    private JwtTokenVerifier verifier;

    @BeforeEach
    void start() throws Exception {
        clock = new ControllableClock(Instant.parse("2026-10-19T10:00:00Z"));
        idp = new FakeIdentityProvider(clock).start();
        verifier = verifierFor(idp);
    }

    @AfterEach
    void stop() {
        idp.close();
    }

    private JwtTokenVerifier verifierFor(FakeIdentityProvider provider) {
        return JwtTokenVerifier.forJwks(provider.issuer(), provider.jwksUri(), clock, Duration.ofSeconds(30),
                "tenant", "sub_kind", Duration.ofSeconds(2));
    }

    private Map<String, Object> claims() {
        Map<String, Object> claims = idp.baseClaims("alice", AUDIENCE, Duration.ofMinutes(5));
        claims.put("scope", "oa.inspect oa.operate Not_A_Scope");
        claims.put("tenant", "t-1");
        return claims;
    }

    private void assertRejected(Reason expected, String token) {
        TokenRejectedException e = assertThrows(TokenRejectedException.class, () -> verifier.verify(token, AUDIENCE));
        assertEquals(expected, e.reason());
        assertTrue(token.isEmpty() || !String.valueOf(e.getMessage()).contains(token),
                "the token must not appear in the message");
    }

    @Test
    void acceptsAValidTokenAndTakesSubjectTenantAndScopesFromItsClaims() throws Exception {
        VerifiedToken token = verifier.verify(idp.signClaims(claims()), AUDIENCE);

        assertEquals("alice", token.subject().id());
        assertEquals(SubjectKind.USER, token.subject().kind());
        assertEquals("t-1", token.tenant());
        assertEquals(java.util.Set.of("oa.inspect", "oa.operate"), token.scopes(),
                "a scope outside the policy syntax is dropped, never kept");
        assertFalse(token.isDelegated());
        assertEquals(idp.issuer(), token.issuer());
        assertEquals(clock.instant().plusSeconds(300).getEpochSecond(), token.expiresAt().getEpochSecond());
    }

    @Test
    void acceptsAnArrayAudienceThatContainsTheExpectedOne() throws Exception {
        Map<String, Object> claims = claims();
        claims.put("aud", List.of("account", AUDIENCE));
        assertEquals("alice", verifier.verify(idp.signClaims(claims), AUDIENCE).subject().id());
    }

    @Test
    void readsTheSubjectKindClaimAndTheActorChain() throws Exception {
        Map<String, Object> claims = claims();
        claims.put("sub_kind", "AGENT");
        claims.put("act", Map.of("sub", "agent-7", "act", Map.of("sub", "agent-3")));
        VerifiedToken token = verifier.verify(idp.signClaims(claims), AUDIENCE);
        assertEquals(SubjectKind.AGENT, token.subject().kind());
        assertEquals(List.of("agent-7", "agent-3"), token.actors());
        assertTrue(token.isDelegated());
        assertEquals("agent-7", token.actor());
    }

    @Test
    void rejectsAnExpiredTokenOnlyAfterTheSkew() throws Exception {
        String token = idp.signClaims(claims());
        clock.advance(Duration.ofSeconds(300 + 29));
        assertEquals("alice", verifier.verify(token, AUDIENCE).subject().id(), "inside the 30 s skew");
        clock.advance(Duration.ofSeconds(1));
        assertRejected(Reason.EXPIRED, token);
    }

    @Test
    void rejectsATokenWithoutAnExpiry() throws Exception {
        Map<String, Object> claims = claims();
        claims.remove("exp");
        assertRejected(Reason.MISSING_CLAIM, idp.signClaims(claims));
    }

    @Test
    void rejectsATokenNotValidYetAndOneIssuedInTheFuture() throws Exception {
        Map<String, Object> notBefore = claims();
        notBefore.put("nbf", clock.instant().plusSeconds(120).getEpochSecond());
        assertRejected(Reason.NOT_YET_VALID, idp.signClaims(notBefore));

        Map<String, Object> future = claims();
        future.put("iat", clock.instant().plusSeconds(120).getEpochSecond());
        assertRejected(Reason.NOT_YET_VALID, idp.signClaims(future));
    }

    @Test
    void rejectsAWrongAudienceAndAMissingAudience() throws Exception {
        Map<String, Object> other = claims();
        other.put("aud", "tool-search");
        assertRejected(Reason.AUDIENCE, idp.signClaims(other));

        Map<String, Object> none = claims();
        none.remove("aud");
        assertRejected(Reason.AUDIENCE, idp.signClaims(none));
    }

    @Test
    void rejectsAWrongIssuer() throws Exception {
        Map<String, Object> claims = claims();
        claims.put("iss", "https://evil.example/realm");
        assertRejected(Reason.ISSUER, idp.signClaims(claims));
    }

    @Test
    void rejectsATokenSignedByAnotherKeyEvenWithTheRightIssuerAndKeyId() throws Exception {
        try (FakeIdentityProvider other = new FakeIdentityProvider(clock).start()) {
            Map<String, Object> claims = claims();
            // Same issuer string and the same key id as the trusted issuer, but another private key.
            claims.put("iss", idp.issuer());
            assertRejected(Reason.SIGNATURE, other.signClaims(claims));
        }
    }

    @Test
    void rejectsATamperedPayload() throws Exception {
        String token = idp.signClaims(claims());
        String[] parts = token.split("\\.");
        Map<String, Object> bigger = claims();
        bigger.put("scope", "oa.inspect oa.operate oa.recover");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(bigger));
        assertRejected(Reason.SIGNATURE, parts[0] + "." + forgedPayload + "." + parts[2]);
    }

    @Test
    void rejectsAnUnsignedTokenWithAlgNone() throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(claims()));
        assertRejected(Reason.SIGNATURE, header + "." + payload + ".");
        assertRejected(Reason.MALFORMED, header + "." + payload + ".AAAA");
    }

    @Test
    void rejectsAnHmacTokenSignedWithThePublicKeyAsTheSecret() throws Exception {
        // The algorithm-confusion attack: HS256 with the (public) RSA key bytes as the shared secret.
        byte[] secret = idp.publicKey().getEncoded();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT)
                .keyID(FakeIdentityProvider.KEY_ID).build();
        JWTClaimsSet.Builder set = new JWTClaimsSet.Builder().issuer(idp.issuer()).subject("mallory")
                .audience(AUDIENCE).expirationTime(java.util.Date.from(clock.instant().plusSeconds(300)));
        SignedJWT jwt = new SignedJWT(header, set.build());
        jwt.sign(new MACSigner(secret));
        assertRejected(Reason.SIGNATURE, jwt.serialize());
    }

    @Test
    void rejectsGarbageEmptyAndOversizedTokens() {
        assertRejected(Reason.MALFORMED, "not-a-jwt");
        assertRejected(Reason.MALFORMED, "a.b.c");
        assertRejected(Reason.MISSING, "");
        assertRejected(Reason.MALFORMED, "x".repeat(JwtTokenVerifier.MAX_TOKEN_CHARS + 1));
    }

    @Test
    void rejectsAMalformedActorOrTenantClaim() throws Exception {
        Map<String, Object> badActor = claims();
        badActor.put("act", "agent-7");
        assertRejected(Reason.MALFORMED, idp.signClaims(badActor));

        Map<String, Object> badTenant = claims();
        badTenant.put("tenant", List.of("a"));
        assertRejected(Reason.MALFORMED, idp.signClaims(badTenant));

        Map<String, Object> badKind = claims();
        badKind.put("sub_kind", "ROOT");
        assertRejected(Reason.MALFORMED, idp.signClaims(badKind));
    }

    @Test
    void refusesWhenTheKeysCannotBeFetchedAndNeverAccepts() throws Exception {
        String token = idp.signClaims(claims());
        JwtTokenVerifier fresh = verifierFor(idp);
        idp.close();
        TokenRejectedException e = assertThrows(TokenRejectedException.class, () -> fresh.verify(token, AUDIENCE));
        assertEquals(Reason.VERIFIER_UNAVAILABLE, e.reason());
    }

    @Test
    void refusesAnInsecureJwksUriForARemoteHost() {
        assertThrows(IllegalArgumentException.class, () -> JwtTokenVerifier.forJwks("https://sso.example",
                "http://sso.example/certs", clock, Duration.ofSeconds(30), "tenant", "sub_kind",
                Duration.ofSeconds(2)));
    }
}
