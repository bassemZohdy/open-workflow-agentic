package io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * An error whose detail is written by the runtime, for the client, and is safe to show, including for a server status
 * such as 503. {@link ProblemDetailMapper} shows its detail; any other server error gets the generic detail.
 */
public final class ProblemException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    public ProblemException(Response.Status status, String detail) {
        super(detail, status);
    }

    public ProblemException(int status, String detail) {
        super(detail, status);
    }
}
