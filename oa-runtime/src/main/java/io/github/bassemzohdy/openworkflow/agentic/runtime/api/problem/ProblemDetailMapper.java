package io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.ProblemDetail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.correlation.CorrelationIdFilter;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Turns every unhandled exception into an RFC 9457 problem document ({@code application/problem+json}).
 * Client errors keep their status and message. Server errors return a generic detail, so internal messages are
 * not exposed; the full error is logged under the correlation id that the client receives.
 */
@Provider
public class ProblemDetailMapper implements ExceptionMapper<Throwable> {

    public static final String MEDIA_TYPE = "application/problem+json";
    static final String GENERIC_SERVER_DETAIL =
            "An unexpected error occurred. Quote the correlation id when reporting it.";

    private static final Logger LOG = Logger.getLogger(ProblemDetailMapper.class);

    @Override
    public Response toResponse(Throwable error) {
        Object raw = MDC.get(CorrelationIdFilter.MDC_KEY);
        String correlationId = raw instanceof String s ? s : null;
        ProblemDetail problem = toProblem(error);
        if (problem.status() >= 500) {
            LOG.errorf(error, "Request failed with status %d (correlationId=%s)", problem.status(), correlationId);
        } else {
            LOG.debugf("Request rejected with status %d (correlationId=%s): %s",
                    problem.status(), correlationId, problem.detail());
        }
        return Response.status(problem.status())
                .type(MEDIA_TYPE)
                .entity(toBody(problem, correlationId))
                .build();
    }

    static ProblemDetail toProblem(Throwable error) {
        int status = 500;
        String detail = GENERIC_SERVER_DETAIL;
        if (error instanceof ProblemException problem && problem.getResponse() != null) {
            status = problem.getResponse().getStatus();
            detail = problem.getMessage();
        } else if (error instanceof WebApplicationException web && web.getResponse() != null
                && web.getResponse().getStatus() >= 400) {
            status = web.getResponse().getStatus();
            detail = web.getMessage();
        }
        if (status >= 500 && !(error instanceof ProblemException)) {
            detail = GENERIC_SERVER_DETAIL;
        }
        Response.Status known = Response.Status.fromStatusCode(status);
        String title = known != null ? known.getReasonPhrase() : "Error";
        return new ProblemDetail(ProblemDetail.ABOUT_BLANK, title, status, detail, null);
    }

    /** Members of RFC 9457 plus the {@code correlationId} extension, flattened to top level as the RFC requires. */
    static Map<String, Object> toBody(ProblemDetail problem, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.type().toString());
        body.put("title", problem.title());
        body.put("status", problem.status());
        if (problem.detail() != null) {
            body.put("detail", problem.detail());
        }
        if (problem.instance() != null) {
            body.put("instance", problem.instance().toString());
        }
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        return body;
    }
}
