package io.github.bassemzohdy.openworkflow.agentic;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Test-only endpoint that fails with an internal message, to check the problem mapping. */
@Path("/test/boom")
public class BoomResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String boom() {
        throw new IllegalStateException("boom: internal detail that must not leak");
    }
}
