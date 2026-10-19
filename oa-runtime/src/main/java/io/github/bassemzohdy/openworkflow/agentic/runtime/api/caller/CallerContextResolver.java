package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the {@link CallerContext} of a request. Authentication does not exist yet (WP-102), so the context comes from
 * the headers {@code X-Tenant}, {@code X-Subject} and {@code X-Scopes} only when
 * {@code agentic.dev.trust-caller-headers=true}. With the default (false) every request without authentication is
 * refused with 401, so no endpoint is open by accident, and no scope claim is read from an untrusted request.
 *
 * <p>The headers are a development convenience: anyone who can reach the port can claim any tenant. Do not enable the
 * flag in a deployment.
 */
@ApplicationScoped
public class CallerContextResolver {

    public static final String HEADER_TENANT = "X-Tenant";
    public static final String HEADER_SUBJECT = "X-Subject";
    /** Scopes the caller holds, comma separated. Read only when the development headers are trusted. */
    public static final String HEADER_SCOPES = "X-Scopes";

    private static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final boolean trustCallerHeaders;

    @Inject
    public CallerContextResolver(
            @ConfigProperty(name = "agentic.dev.trust-caller-headers", defaultValue = "false")
            boolean trustCallerHeaders) {
        this.trustCallerHeaders = trustCallerHeaders;
    }

    /**
     * The caller of this request.
     *
     * @throws WebApplicationException 401 when no caller can be established, 400 when a header is malformed
     */
    public CallerContext resolve(HttpHeaders headers) {
        if (!trustCallerHeaders) {
            throw new WebApplicationException(
                    "no caller identity: authentication is not available in this build",
                    Response.Status.UNAUTHORIZED);
        }
        String tenant = headers.getHeaderString(HEADER_TENANT);
        String subject = headers.getHeaderString(HEADER_SUBJECT);
        if (isBlank(tenant) || isBlank(subject)) {
            throw new WebApplicationException(
                    "X-Tenant and X-Subject are required", Response.Status.UNAUTHORIZED);
        }
        Set<String> scopes = scopes(headers.getHeaderString(HEADER_SCOPES));
        try {
            return new CallerContext(tenant.trim(), new Subject(SubjectKind.USER, subject.trim()), scopes);
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

    /** True when the development headers are trusted. Exposed for tests and for the startup log. */
    public boolean trustsCallerHeaders() {
        return trustCallerHeaders;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
