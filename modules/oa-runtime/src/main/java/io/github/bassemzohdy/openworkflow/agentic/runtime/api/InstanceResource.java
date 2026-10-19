package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionNotFoundException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.IdempotencyKeyReusedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Instance API (ADR 0019): start with an idempotency key, and read the registry. Reads never touch the engine's tables.
 *
 * <ul>
 *   <li>{@code POST /instances}: 202 with a Location for a new instance; 200 with the same body when the key was used
 *       before (nothing is started again); 409 when the key was used for a different request; 422 when the engine does
 *       not hold the pinned definition; 400 for a malformed body; 401 without a caller.</li>
 *   <li>{@code GET /instances/{id}}: 200, or 404 for an unknown id or an id of another tenant.</li>
 * </ul>
 */
@Path("/instances")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InstanceResource {

    /** The body of a start request. */
    public record StartBody(String definitionId, String definitionVersion, Map<String, Object> input,
                            String idempotencyKey) {
    }

    /** The public view of a registry row. The input is not returned. */
    public record InstanceView(String id, String definitionId, String definitionVersion, String status,
                               String engineInstanceId, int startAttempts, long version, Instant createdAt,
                               Instant updatedAt) {
    }

    @Inject
    InstanceService service;

    @Inject
    CallerContextResolver callers;

    @POST
    public Response start(@Context HttpHeaders headers, @Context UriInfo uri, StartBody body) {
        CallerContext caller = callers.resolve(headers);
        if (body == null) {
            throw new WebApplicationException("the request body is required", Response.Status.BAD_REQUEST);
        }
        StartRequest request;
        try {
            request = new StartRequest(caller.tenant(), caller.subject(),
                    new DefinitionRef(body.definitionId(), body.definitionVersion()),
                    body.input() == null ? Map.of() : body.input(),
                    body.idempotencyKey());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException("the request is not valid: " + e.getMessage(),
                    Response.Status.BAD_REQUEST);
        }
        StartResult result;
        try {
            result = service.start(request);
        } catch (DefinitionNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), 422);
        } catch (IdempotencyKeyReusedException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
        InstanceRecord row = result.record();
        URI location = uri.getBaseUriBuilder().path(InstanceResource.class).path(row.id().value()).build();
        Response.Status status = result.created() ? Response.Status.ACCEPTED : Response.Status.OK;
        return Response.status(status).location(location).entity(view(row)).build();
    }

    @GET
    @Path("/{id}")
    public InstanceView get(@Context HttpHeaders headers, @PathParam("id") String id) {
        CallerContext caller = callers.resolve(headers);
        Ulid instanceId = parseId(id);
        Optional<InstanceRecord> row = service.get(caller.tenant(), instanceId);
        return row.map(InstanceResource::view)
                .orElseThrow(() -> new WebApplicationException("no such instance", Response.Status.NOT_FOUND));
    }

    private static Ulid parseId(String text) {
        try {
            return Ulid.parse(text);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException("no such instance", Response.Status.NOT_FOUND);
        }
    }

    static InstanceView view(InstanceRecord row) {
        return new InstanceView(row.id().value(), row.definition().definitionId(), row.definition().version(),
                row.status().name(), row.engineInstanceId(), row.startAttempts(), row.version(), row.createdAt(),
                row.updatedAt());
    }
}
