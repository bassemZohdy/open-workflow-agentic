package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentitySettings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Builds the {@link CallerContext} of a request (WP-102, docs/identity.md section 2).
 *
 * <p><b>Verified path.</b> A request with an {@code Authorization: Bearer} header is authenticated by the
 * {@link TokenVerifier}: signature, issuer, audience (this API's), expiry. Tenant, subject and scopes come from the
 * token's claims. {@code X-Tenant}, {@code X-Subject} and {@code X-Scopes} are not read at all on this path, even when
 * the development seam is on, so a request cannot mix a real token with claimed scopes. A delegated token (one with an
 * {@code act} claim) is not a caller credential and is refused. A missing, malformed, expired, wrongly signed or
 * wrong-audience token is 401 with a {@code WWW-Authenticate} header and a problem body; a verifier that cannot decide
 * is 503. Nothing is ever accepted because it could not be checked.
 *
 * <p><b>Development seam.</b> Without an {@code Authorization} header, the headers {@code X-Tenant}, {@code X-Subject}
 * and {@code X-Scopes} are read only when {@code agentic.dev.trust-caller-headers=true} and the active profile is one
 * of {@code agentic.dev.header-seam-profiles} (default {@code dev,test}). The profiles {@code prod} and
 * {@code production} can never enable it. If the flag is true outside the allowed profiles, the application refuses to
 * start. With the default (false) a request without a token is 401.
 */
@ApplicationScoped
public class CallerContextResolver {

    public static final String HEADER_TENANT = "X-Tenant";
    public static final String HEADER_SUBJECT = "X-Subject";
    /** Scopes the caller holds, comma separated. Read only when the development headers are trusted. */
    public static final String HEADER_SCOPES = "X-Scopes";

    /** Profiles that can never enable the development seam. */
    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern BEARER = Pattern.compile("(?i)^bearer +([^ ,]+)$");
    private static final Logger LOG = Logger.getLogger(CallerContextResolver.class);

    private final TokenVerifier verifier;
    private final String audience;
    private final boolean seamEnabled;
    private final java.time.Clock clock;

    @Inject
    public CallerContextResolver(
            TokenVerifier verifier,
            IdentitySettings identity,
            @ConfigProperty(name = "agentic.dev.trust-caller-headers", defaultValue = "false")
            boolean trustCallerHeaders,
            @ConfigProperty(name = "agentic.dev.header-seam-profiles", defaultValue = "dev,test")
            String seamProfiles) {
        this(verifier, identity.audience().orElse(null), trustCallerHeaders, csv(seamProfiles),
                Set.copyOf(ConfigUtils.getProfiles()));
    }

    /**
     * @param verifier           checks bearer tokens
     * @param audience           the audience of this API, or null when none is configured (then no token is accepted)
     * @param trustCallerHeaders the value of {@code agentic.dev.trust-caller-headers}
     * @param seamProfiles       profiles in which the flag may take effect
     * @param activeProfiles     the active profiles
     * @throws IllegalStateException if the flag is true and the active profiles do not allow it
     */
    public CallerContextResolver(TokenVerifier verifier, String audience, boolean trustCallerHeaders,
                                 Set<String> seamProfiles, Set<String> activeProfiles) {
        this(verifier, audience, trustCallerHeaders, seamProfiles, activeProfiles, java.time.Clock.systemUTC());
    }

    /** As above, with the clock that {@link CallerAuthority#requireCurrent} samples (tests move it). */
    public CallerContextResolver(TokenVerifier verifier, String audience, boolean trustCallerHeaders,
                                 Set<String> seamProfiles, Set<String> activeProfiles, java.time.Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.audience = audience == null || audience.isBlank() ? null : audience;
        Set<String> allowed = seamProfiles.stream().map(CallerContextResolver::normalise)
                .filter(profile -> !PRODUCTION_PROFILES.contains(profile)).collect(Collectors.toSet());
        Set<String> active = activeProfiles.stream().map(CallerContextResolver::normalise).collect(Collectors.toSet());
        boolean allowedHere = active.stream().noneMatch(PRODUCTION_PROFILES::contains)
                && !active.isEmpty() && allowed.containsAll(active);
        if (trustCallerHeaders && !allowedHere) {
            throw new IllegalStateException("agentic.dev.trust-caller-headers=true is refused in profile(s) " + active
                    + ": the caller headers are a development convenience and are accepted only in " + allowed);
        }
        this.seamEnabled = trustCallerHeaders;
        if (seamEnabled) {
            LOG.warn("agentic.dev.trust-caller-headers is ON: X-Tenant, X-Subject and X-Scopes are trusted for"
                    + " requests without a bearer token. This is for development and tests only.");
        }
    }

    /** Forces construction at startup, so a refused configuration stops the application before it serves anything. */
    void onStart(@Observes StartupEvent event) {
        LOG.infof("caller identity: bearer tokens %s; development header seam %s",
                audience == null ? "are refused (no issuer or audience configured)" : "are verified for audience "
                        + audience, seamEnabled ? "ON" : "off");
    }

    /**
     * The caller of this request.
     *
     * @throws WebApplicationException 401 when no caller can be established or the token is not acceptable, 400 when a
     *                                 development header is malformed, 503 when the token could not be checked
     */
    public CallerContext resolve(HttpHeaders headers) {
        return resolveAuthority(headers).caller();
    }

    /**
     * The caller of this request with the handle that proves its authority is still current (WP-108). A verified token
     * keeps its signed claims privately; the development seam yields a handle that is never current.
     */
    public CallerAuthority resolveAuthority(HttpHeaders headers) {
        List<String> authorization = headers.getRequestHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isEmpty()) {
            return fromToken(authorization);
        }
        if (!seamEnabled) {
            throw unauthorized("authentication is required: send a bearer token", false);
        }
        return CallerAuthority.unverified(fromDevelopmentHeaders(headers), clock);
    }

    // ---- verified path -------------------------------------------------------------------------------------------------

    private CallerAuthority fromToken(List<String> authorization) {
        if (authorization.size() != 1) {
            throw unauthorized("the bearer token is not valid", true);
        }
        var matcher = BEARER.matcher(authorization.get(0).trim());
        if (!matcher.matches()) {
            throw unauthorized("the bearer token is not valid", true);
        }
        VerifiedToken token;
        try {
            if (audience == null) {
                throw new TokenRejectedException(TokenRejectedException.Reason.VERIFIER_UNAVAILABLE);
            }
            token = verifier.verify(matcher.group(1), audience);
            if (token.isDelegated()) {
                // A delegated token is for a tool, not for this API (ADR 0021: no token passthrough).
                throw new TokenRejectedException(TokenRejectedException.Reason.WRONG_TOKEN_KIND);
            }
            if (token.tenant() == null) {
                throw new TokenRejectedException(TokenRejectedException.Reason.MISSING_CLAIM);
            }
        } catch (TokenRejectedException rejected) {
            LOG.debugf("bearer token refused: %s", rejected.reason());
            switch (rejected.reason()) {
                case VERIFIER_UNAVAILABLE -> throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                        "the bearer token could not be verified; try again later");
                case EXPIRED -> throw unauthorized("the bearer token has expired", true);
                default -> throw unauthorized("the bearer token is not valid", true);
            }
        } catch (RuntimeException unexpected) {
            LOG.warnf(unexpected, "bearer token verification failed unexpectedly");
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the bearer token could not be verified; try again later");
        }
        return CallerAuthority.ofUserToken(token, clock);
    }

    private static WebApplicationException unauthorized(String detail, boolean tokenPresented) {
        String challenge = tokenPresented ? "Bearer error=\"invalid_token\"" : "Bearer";
        return new WebApplicationException(detail, Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, challenge).build());
    }

    // ---- development seam ----------------------------------------------------------------------------------------------

    private CallerContext fromDevelopmentHeaders(HttpHeaders headers) {
        String tenant = headers.getHeaderString(HEADER_TENANT);
        String subject = headers.getHeaderString(HEADER_SUBJECT);
        if (isBlank(tenant) || isBlank(subject)) {
            throw new WebApplicationException(
                    "X-Tenant and X-Subject are required", Response.Status.UNAUTHORIZED);
        }
        Set<String> scopes = scopes(headers.getHeaderString(HEADER_SCOPES));
        try {
            return new CallerContext(tenant.trim(), new Subject(SubjectKind.USER, subject.trim()), scopes,
                    CallerContext.Provenance.DEV_HEADERS);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("X-Tenant or X-Subject is malformed", Response.Status.BAD_REQUEST);
        }
    }

    /** The scopes named in the header: comma separated, each a token of letters, digits and ._:- (at most 64). */
    private static Set<String> scopes(String header) {
        if (isBlank(header)) {
            return Set.of();
        }
        Set<String> scopes = new TreeSet<>();
        for (String token : header.split(",")) {
            String scope = token.trim();
            if (scope.isEmpty()) {
                continue;
            }
            if (!SCOPE.matcher(scope).matches()) {
                throw new WebApplicationException("X-Scopes is malformed", Response.Status.BAD_REQUEST);
            }
            scopes.add(scope);
        }
        return scopes;
    }

    /** True when the development headers are trusted (the flag is on and the profile allows it). */
    public boolean trustsCallerHeaders() {
        return seamEnabled;
    }

    private static Set<String> csv(String text) {
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static String normalise(String profile) {
        return profile.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
