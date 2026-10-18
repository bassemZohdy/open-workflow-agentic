package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the {@link CallerContext} of a request. Authentication does not exist yet (WP-102), so the context comes from
 * the headers {@code X-Tenant} and {@code X-Subject} only when {@code agentic.dev.trust-caller-headers=true}. With the
 * default (false) every request without authentication is refused with 401, so no endpoint is open by accident.
 *
 * <p>The headers are a development convenience: anyone who can reach the port can claim any tenant. Do not enable the
 * flag in a deployment.
 */
@ApplicationScoped
public class CallerContextResolver {

    public static final String HEADER_TENANT = "X-Tenant";
    public static final String HEADER_SUBJECT = "X-Subject";

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
        try {
            return new CallerContext(tenant.trim(), new Subject(SubjectKind.USER, subject.trim()));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("X-Tenant or X-Subject is malformed", Response.Status.BAD_REQUEST);
        }
    }

    /** True when the development headers are trusted. Exposed for tests and for the startup log. */
    public boolean trustsCallerHeaders() {
        return trustCallerHeaders;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
