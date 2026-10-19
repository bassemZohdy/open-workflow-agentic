package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies access tokens (JWT, JWS) against the issuer's published keys (a JWKS). Library: nimbus-jose-jwt.
 *
 * <p>What is checked, in this order, and what is refused:
 * <ol>
 *   <li>Size: a token over {@value #MAX_TOKEN_CHARS} characters is refused before parsing.</li>
 *   <li>Signature, by the library: only asymmetric algorithms (RS256/384/512, PS256/384/512, ES256/384/512) are
 *       accepted. {@code none}, HMAC algorithms and encrypted tokens are refused, so a token cannot be signed with the
 *       public key as an HMAC secret. An unknown key id leads to one rate-limited refetch of the JWKS, then a refusal.
 *       A JWKS that cannot be fetched and has no cached key gives {@link Reason#VERIFIER_UNAVAILABLE}, never an accept.
 *       The library's own time checks are switched off, so that every time decision uses this class's {@link Clock}.</li>
 *   <li>Issuer: exact match with the configured issuer.</li>
 *   <li>Audience: the expected audience must be among the token's audiences ({@code aud} may be a string or an array).</li>
 *   <li>Time: {@code exp} is required and the token is refused once {@code now >= exp + skew}; {@code nbf} and
 *       {@code iat} in the future beyond the skew are refused.</li>
 *   <li>{@code sub} is required.</li>
 * </ol>
 *
 * <p>Clock skew: a token is accepted until {@code skew} after its expiry. The default is 30 seconds. With delegated
 * tokens that live at most 300 seconds, this is the extra time an expired token could still be used.
 */
public final class JwtTokenVerifier implements TokenVerifier {

    /** Longest token accepted, in characters. Keycloak access tokens are well below this. */
    public static final int MAX_TOKEN_CHARS = 16 * 1024;
    /** Depth limit of the {@code act} chain. */
    public static final int MAX_ACTOR_DEPTH = 8;
    /** Most scopes taken from a token. */
    static final int MAX_SCOPES = AgentTaskContract.MAX_ALLOW_LIST_SIZE;

    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);

    private final String issuer;
    private final Clock clock;
    private final Duration skew;
    private final String tenantClaim;
    private final String subjectKindClaim;
    private final DefaultJWTProcessor<SecurityContext> processor;

    /**
     * @param issuer           the exact {@code iss} value to accept
     * @param keys             the source of the issuer's public keys
     * @param clock            the time source for every expiry decision
     * @param skew             accepted clock difference, 0 to 5 minutes
     * @param tenantClaim      name of the claim that holds the tenant
     * @param subjectKindClaim name of the claim that holds the subject kind (USER, AGENT, WORKFLOW, SERVICE); a token
     *                         without it is a USER token
     */
    public JwtTokenVerifier(String issuer, JWKSource<SecurityContext> keys, Clock clock, Duration skew,
                            String tenantClaim, String subjectKindClaim) {
        this.issuer = requireText(issuer, "issuer");
        Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(skew, "skew");
        if (skew.isNegative() || skew.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("clock skew must be between 0 and 5 minutes");
        }
        this.skew = skew;
        this.tenantClaim = requireText(tenantClaim, "tenantClaim");
        this.subjectKindClaim = requireText(subjectKindClaim, "subjectKindClaim");
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
        this.processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT,
                new JOSEObjectType("at+jwt"), null));
        // The library would compare exp and nbf with the system clock. All time decisions are made below, with the
        // injected clock, so the library's claims check is replaced by one that accepts any claim set.
        this.processor.setJWTClaimsSetVerifier((claims, context) -> { });
    }

    /**
     * A verifier that fetches the keys from {@code jwksUri} (cached for 5 minutes, refetched at most every 30 seconds
     * for an unknown key id, no stale keys served during an outage).
     */
    public static JwtTokenVerifier forJwks(String issuer, String jwksUri, Clock clock, Duration skew,
                                           String tenantClaim, String subjectKindClaim, Duration httpTimeout) {
        URI uri = URI.create(requireText(jwksUri, "jwksUri"));
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean loopback = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw new IllegalArgumentException("the JWKS URI must use https (http only for a loopback host)");
        }
        int millis = (int) Math.min(httpTimeout.toMillis(), Integer.MAX_VALUE);
        try {
            JWKSource<SecurityContext> source = JWKSourceBuilder
                    .create(uri.toURL(), new DefaultResourceRetriever(millis, millis, 256 * 1024, false))
                    .retrying(true)
                    .cache(Duration.ofMinutes(5).toMillis(), millis)
                    .rateLimited(Duration.ofSeconds(30).toMillis())
                    .build();
            return new JwtTokenVerifier(issuer, source, clock, skew, tenantClaim, subjectKindClaim);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("the JWKS URI is not a URL", e);
        }
    }

    @Override
    public VerifiedToken verify(String token, String expectedAudience) {
        if (token == null || token.isBlank()) {
            throw new TokenRejectedException(Reason.MISSING);
        }
        Objects.requireNonNull(expectedAudience, "expectedAudience");
        if (token.length() > MAX_TOKEN_CHARS) {
            throw new TokenRejectedException(Reason.MALFORMED);
        }
        JWTClaimsSet claims = process(token);
        try {
            return check(claims, expectedAudience);
        } catch (ParseException | ClassCastException e) {
            throw new TokenRejectedException(Reason.MALFORMED, e);
        }
    }

    private JWTClaimsSet process(String token) {
        try {
            return processor.process(token, null);
        } catch (ParseException e) {
            throw new TokenRejectedException(Reason.MALFORMED, e);
        } catch (BadJOSEException | JOSEException e) {
            // A key source that cannot be reached is "could not decide"; anything else is a refusal of this token.
            // Both refuse; the difference is only the status the caller answers with.
            throw new TokenRejectedException(keySourceFailed(e) ? Reason.VERIFIER_UNAVAILABLE : Reason.SIGNATURE, e);
        } catch (RuntimeException e) {
            throw new TokenRejectedException(Reason.VERIFIER_UNAVAILABLE, e);
        }
    }

    private static boolean keySourceFailed(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof KeySourceException || t instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    private VerifiedToken check(JWTClaimsSet claims, String expectedAudience) throws ParseException {
        if (!issuer.equals(claims.getIssuer())) {
            throw new TokenRejectedException(Reason.ISSUER);
        }
        List<String> audiences = claims.getAudience();
        if (audiences == null || !audiences.contains(expectedAudience)) {
            throw new TokenRejectedException(Reason.AUDIENCE);
        }
        Date exp = claims.getExpirationTime();
        if (exp == null) {
            throw new TokenRejectedException(Reason.MISSING_CLAIM);
        }
        Instant now = clock.instant();
        if (!now.isBefore(exp.toInstant().plus(skew))) {
            throw new TokenRejectedException(Reason.EXPIRED);
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && nbf.toInstant().minus(skew).isAfter(now)) {
            throw new TokenRejectedException(Reason.NOT_YET_VALID);
        }
        Date iat = claims.getIssueTime();
        if (iat != null && iat.toInstant().minus(skew).isAfter(now)) {
            throw new TokenRejectedException(Reason.NOT_YET_VALID);
        }
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank() || sub.length() > Subject.MAX_ID_LENGTH) {
            throw new TokenRejectedException(Reason.MISSING_CLAIM);
        }
        SubjectKind kind = subjectKind(claims.getClaim(subjectKindClaim));
        Object tenantValue = claims.getClaim(tenantClaim);
        String tenant = tenantValue instanceof String text && !text.isBlank() && text.length() <= 256 ? text : null;
        if (tenantValue != null && tenant == null) {
            throw new TokenRejectedException(Reason.MALFORMED);
        }
        return new VerifiedToken(issuer, new Subject(kind, sub), Set.copyOf(audiences), scopes(claims),
                tenant, iat == null ? null : iat.toInstant(), exp.toInstant(), claims.getJWTID(),
                actors(claims.getClaim("act")));
    }

    private static SubjectKind subjectKind(Object claim) {
        if (claim == null) {
            return SubjectKind.USER;
        }
        if (claim instanceof String text) {
            try {
                return SubjectKind.valueOf(text);
            } catch (IllegalArgumentException e) {
                throw new TokenRejectedException(Reason.MALFORMED, e);
            }
        }
        throw new TokenRejectedException(Reason.MALFORMED);
    }

    /**
     * The scopes of the {@code scope} claim (space separated, as RFC 8693 and Keycloak write it). A scope that does not
     * have the syntax the policy context accepts is dropped: dropping a scope can only narrow authority.
     */
    private static Set<String> scopes(JWTClaimsSet claims) throws ParseException {
        Object raw = claims.getClaim("scope");
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof String text)) {
            throw new TokenRejectedException(Reason.MALFORMED);
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : text.trim().split("\\s+")) {
            if (!scope.isEmpty() && AgentTaskContract.TOKEN.matcher(scope).matches()) {
                scopes.add(scope);
            }
            if (scopes.size() >= MAX_SCOPES) {
                break;
            }
        }
        return scopes;
    }

    @SuppressWarnings("unchecked")
    private static List<String> actors(Object claim) {
        List<String> chain = new ArrayList<>();
        Object current = claim;
        while (current != null) {
            if (!(current instanceof Map<?, ?> map) || chain.size() >= MAX_ACTOR_DEPTH) {
                throw new TokenRejectedException(Reason.MALFORMED);
            }
            Object sub = ((Map<String, Object>) map).get("sub");
            if (!(sub instanceof String text) || text.isBlank() || text.length() > Subject.MAX_ID_LENGTH) {
                throw new TokenRejectedException(Reason.MALFORMED);
            }
            chain.add(text);
            current = ((Map<String, Object>) map).get("act");
        }
        return chain;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
