package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.util.Map;

/** Structured CloudEvents ingress. Route is transport metadata, never a trusted event tenant/target extension. */
@Path("/instances/{instance}/events/{correlation}")
@Produces(MediaType.APPLICATION_JSON)
public class EventIngressResource {
    @Inject CallerContextResolver callers;
    @Inject EventIngress ingress;

    @POST @Consumes(StructuredEvent.CONTENT_TYPE)
    public Response receive(@Context HttpHeaders headers, @PathParam("instance") String id,
                            @PathParam("correlation") String correlation, InputStream body) {
        var caller = callers.resolve(headers);
        Ulid instance;
        try { instance = Ulid.parse(id); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new ProblemException(404, "no such instance"); }
        var result = ingress.receive(caller, instance, correlation, StructuredEvent.read(body));
        return Response.status(result.created() ? 202 : 200).entity(Map.of("id", result.message().eventId(),
                "instanceId", instance.value(), "outcome", "received", "expiresAt", result.retainUntil().toString())).build();
    }
}
