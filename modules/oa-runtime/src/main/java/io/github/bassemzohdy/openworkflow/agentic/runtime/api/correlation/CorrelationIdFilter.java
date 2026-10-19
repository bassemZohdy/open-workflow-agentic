package io.github.bassemzohdy.openworkflow.agentic.runtime.api.correlation;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.CorrelationId;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

/**
 * Reads or creates the correlation id for every request, puts it in the logging context and echoes it in the
 * response header. Pre-matching, so that it also applies to requests that match no resource (404) and to
 * responses produced by exception mappers.
 */
@Provider
@PreMatching
@Priority(Priorities.HEADER_DECORATOR)
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** Logging-context key; the problem mapper reads it. */
    public static final String MDC_KEY = "correlationId";

    static final String PROPERTY = CorrelationIdFilter.class.getName();

    @Override
    public void filter(ContainerRequestContext request) {
        CorrelationId id = CorrelationId.resolve(request.getHeaderString(CorrelationId.HEADER));
        request.setProperty(PROPERTY, id);
        MDC.put(MDC_KEY, id.value());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (request.getProperty(PROPERTY) instanceof CorrelationId id) {
            response.getHeaders().putSingle(CorrelationId.HEADER, id.value());
        }
        MDC.remove(MDC_KEY);
    }
}
