package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.RejectingTokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The caller seam without a container: which source is trusted, and when the development seam is refused. */
class CallerContextResolverTest {

    private static final Set<String> SEAM_PROFILES = Set.of("dev", "test");
    private static final String AUDIENCE = "oa-api";

    /** The token text that a verifier stub accepts. */
    private static final String GOOD = "good.token.value";

    private static VerifiedToken token(String tenant, List<String> actors, Set<String> scopes) {
        return new VerifiedToken("https://issuer.example", new Subject(SubjectKind.USER, "alice"), Set.of(AUDIENCE),
                scopes, tenant, Instant.now(), Instant.now().plusSeconds(60), "jti-1", actors);
    }

    private static TokenVerifier accepting(VerifiedToken token) {
        return (text, audience) -> {
            if (!GOOD.equals(text) || !AUDIENCE.equals(audience)) {
                throw new TokenRejectedException(Reason.SIGNATURE);
            }
            return token;
        };
    }

    private static CallerContextResolver resolver(TokenVerifier verifier, boolean trustHeaders, String... profiles) {
        return new CallerContextResolver(verifier, AUDIENCE, trustHeaders, SEAM_PROFILES, Set.of(profiles));
    }

    private static Headers headers(String... pairs) {
        Headers headers = new Headers();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.add(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    private static int statusOf(Runnable action) {
        WebApplicationException e = assertThrows(WebApplicationException.class, action::run);
        return e.getResponse().getStatus();
    }

    // ---- the production profile refuses the development seam -----------------------------------------------------------

    @Test
    void theProductionProfileRefusesTheSeamEvenWhenThePropertyIsSet() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> resolver(new RejectingTokenVerifier(), true, "prod"));
        assertTrue(refused.getMessage().contains("refused"));
    }

    @Test
    void theProductionProfileCannotBeAddedToTheAllowedProfiles() {
        assertThrows(IllegalStateException.class, () -> new CallerContextResolver(new RejectingTokenVerifier(), AUDIENCE,
                true, Set.of("prod", "production", "dev"), Set.of("prod")));
        assertThrows(IllegalStateException.class, () -> new CallerContextResolver(new RejectingTokenVerifier(), AUDIENCE,
                true, Set.of("prod", "dev"), Set.of("dev", "prod")), "prod among several active profiles");
    }

    @Test
    void anUnknownProfileDoesNotEnableTheSeam() {
        assertThrows(IllegalStateException.class, () -> resolver(new RejectingTokenVerifier(), true, "staging"));
        assertThrows(IllegalStateException.class, () -> resolver(new RejectingTokenVerifier(), true));
    }

    @Test
    void theSeamIsOnlyActiveWhenTheFlagIsSetInAnAllowedProfile() {
        assertFalse(resolver(new RejectingTokenVerifier(), false, "dev").trustsCallerHeaders());
        assertFalse(resolver(new RejectingTokenVerifier(), false, "prod").trustsCallerHeaders(),
                "production with the flag off is the normal case and must start");
        assertTrue(resolver(new RejectingTokenVerifier(), true, "dev").trustsCallerHeaders());
        assertTrue(resolver(new RejectingTokenVerifier(), true, "test").trustsCallerHeaders());
    }

    // ---- the verified path ---------------------------------------------------------------------------------------------

    @Test
    void tenantSubjectAndScopesComeFromTheVerifiedTokenNotFromHeaders() {
        CallerContextResolver resolver = resolver(accepting(token("t-1", List.of(), Set.of("oa.inspect"))), true, "test");

        CallerContext caller = resolver.resolve(headers("Authorization", "Bearer " + GOOD,
                "X-Tenant", "other-tenant", "X-Subject", "mallory", "X-Scopes", "oa.recover,oa.operate"));

        assertEquals("t-1", caller.tenant());
        assertEquals(new Subject(SubjectKind.USER, "alice"), caller.subject());
        assertEquals(Set.of("oa.inspect"), caller.scopes(), "X-Scopes is never read on the verified path");
        assertTrue(caller.verified());
        assertEquals(CallerContext.Provenance.VERIFIED_TOKEN, caller.provenance());
    }

    @Test
    void aBadTokenIsNotRescuedByTrustedHeaders() {
        CallerContextResolver resolver = resolver(accepting(token("t-1", List.of(), Set.of())), true, "test");
        Headers withHeaders = headers("Authorization", "Bearer forged", "X-Tenant", "t-1", "X-Subject", "alice",
                "X-Scopes", "oa.recover");
        assertEquals(401, statusOf(() -> resolver.resolve(withHeaders)));
    }

    @Test
    void aTokenRefusedByTheVerifierIs401WithAChallengeAndAnExpiredOneSaysSo() {
        for (Reason reason : List.of(Reason.SIGNATURE, Reason.AUDIENCE, Reason.ISSUER, Reason.MALFORMED,
                Reason.MISSING_CLAIM, Reason.NOT_YET_VALID)) {
            CallerContextResolver resolver = resolver((t, a) -> {
                throw new TokenRejectedException(reason);
            }, false, "prod");
            WebApplicationException e = assertThrows(WebApplicationException.class,
                    () -> resolver.resolve(headers("Authorization", "Bearer " + GOOD)));
            assertEquals(401, e.getResponse().getStatus(), reason.name());
            assertEquals("Bearer error=\"invalid_token\"", e.getResponse().getHeaderString("WWW-Authenticate"));
            assertEquals("the bearer token is not valid", e.getMessage());
        }
        CallerContextResolver expired = resolver((t, a) -> {
            throw new TokenRejectedException(Reason.EXPIRED);
        }, false, "prod");
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> expired.resolve(headers("Authorization", "Bearer " + GOOD)));
        assertEquals(401, e.getResponse().getStatus());
        assertEquals("the bearer token has expired", e.getMessage());
    }

    @Test
    void aVerifierThatCannotDecideIs503AndNeverAnAccept() {
        CallerContextResolver unavailable = resolver((t, a) -> {
            throw new TokenRejectedException(Reason.VERIFIER_UNAVAILABLE);
        }, false, "prod");
        assertEquals(503, statusOf(() -> unavailable.resolve(headers("Authorization", "Bearer " + GOOD))));

        CallerContextResolver broken = resolver((t, a) -> {
            throw new IllegalStateException("boom");
        }, false, "prod");
        assertEquals(503, statusOf(() -> broken.resolve(headers("Authorization", "Bearer " + GOOD))));

        CallerContextResolver noIssuer = resolver(new RejectingTokenVerifier(), false, "prod");
        assertEquals(503, statusOf(() -> noIssuer.resolve(headers("Authorization", "Bearer " + GOOD))));
    }

    @Test
    void withoutAConfiguredAudienceNoTokenIsAccepted() {
        CallerContextResolver resolver = new CallerContextResolver(accepting(token("t-1", List.of(), Set.of())), null,
                false, SEAM_PROFILES, Set.of("prod"));
        assertEquals(503, statusOf(() -> resolver.resolve(headers("Authorization", "Bearer " + GOOD))));
    }

    @Test
    void aDelegatedTokenIsNotACallerCredential() {
        CallerContextResolver resolver = resolver(accepting(token("t-1", List.of("agent-7"), Set.of("oa.inspect"))),
                false, "prod");
        assertEquals(401, statusOf(() -> resolver.resolve(headers("Authorization", "Bearer " + GOOD))));
    }

    @Test
    void aTokenWithoutATenantIsRefused() {
        CallerContextResolver resolver = resolver(accepting(token(null, List.of(), Set.of("oa.inspect"))), false, "prod");
        assertEquals(401, statusOf(() -> resolver.resolve(headers("Authorization", "Bearer " + GOOD))));
    }

    @Test
    void onlyOneWellFormedBearerHeaderIsAccepted() {
        CallerContextResolver resolver = resolver(accepting(token("t-1", List.of(), Set.of())), false, "prod");
        assertEquals(401, statusOf(() -> resolver.resolve(headers("Authorization", "Basic dXNlcjpwdw=="))));
        assertEquals(401, statusOf(() -> resolver.resolve(headers("Authorization", "Bearer"))));
        assertEquals(401, statusOf(() -> resolver.resolve(headers("Authorization", "Bearer a b"))));
        assertEquals(401, statusOf(() -> resolver.resolve(headers("Authorization", "Bearer " + GOOD,
                "Authorization", "Bearer " + GOOD))));
        assertNotNull(resolver.resolve(headers("Authorization", "bearer " + GOOD)), "the scheme is case-insensitive");
    }

    // ---- the development seam ------------------------------------------------------------------------------------------

    @Test
    void withoutTokenAndSeamTheCallerIs401WithAChallenge() {
        CallerContextResolver resolver = resolver(new RejectingTokenVerifier(), false, "prod");
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> resolver.resolve(headers("X-Tenant", "t1", "X-Subject", "alice", "X-Scopes", "oa.recover")));
        assertEquals(401, e.getResponse().getStatus());
        assertEquals("Bearer", e.getResponse().getHeaderString("WWW-Authenticate"));
    }

    @Test
    void theSeamReadsHeadersWhenOnAndTagsTheCallerAsNotVerified() {
        CallerContextResolver resolver = resolver(new RejectingTokenVerifier(), true, "test");
        CallerContext caller = resolver.resolve(headers("X-Tenant", "t1", "X-Subject", "alice",
                "X-Scopes", "oa.inspect, oa.operate"));
        assertEquals("t1", caller.tenant());
        assertEquals(Set.of("oa.inspect", "oa.operate"), caller.scopes());
        assertFalse(caller.verified());
        assertEquals(CallerContext.Provenance.DEV_HEADERS, caller.provenance());
        assertEquals(401, statusOf(() -> resolver.resolve(headers("X-Tenant", "t1"))));
        assertEquals(400, statusOf(() -> resolver.resolve(headers("X-Tenant", "t1", "X-Subject", "a",
                "X-Scopes", "bad scope!"))));
    }

    @Test
    void aCallerBuiltByHandIsNotVerified() {
        assertFalse(new CallerContext("t", new Subject(SubjectKind.USER, "u")).verified());
        assertFalse(new CallerContext("t", new Subject(SubjectKind.USER, "u"), Set.of("oa.inspect")).verified());
    }

    /** A request's headers, case-insensitive on the name like HTTP. */
    private static final class Headers implements HttpHeaders {

        private final MultivaluedMap<String, String> map = new MultivaluedHashMap<>();

        void add(String name, String value) {
            map.add(name.toLowerCase(Locale.ROOT), value);
        }

        @Override
        public List<String> getRequestHeader(String name) {
            return map.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeaderString(String name) {
            List<String> values = getRequestHeader(name);
            return values == null ? null : String.join(",", values);
        }

        @Override
        public MultivaluedMap<String, String> getRequestHeaders() {
            return map;
        }

        @Override
        public List<MediaType> getAcceptableMediaTypes() {
            return List.of();
        }

        @Override
        public List<Locale> getAcceptableLanguages() {
            return List.of();
        }

        @Override
        public MediaType getMediaType() {
            return null;
        }

        @Override
        public Locale getLanguage() {
            return null;
        }

        @Override
        public Map<String, Cookie> getCookies() {
            return Map.of();
        }

        @Override
        public Date getDate() {
            return null;
        }

        @Override
        public int getLength() {
            return -1;
        }
    }
}
