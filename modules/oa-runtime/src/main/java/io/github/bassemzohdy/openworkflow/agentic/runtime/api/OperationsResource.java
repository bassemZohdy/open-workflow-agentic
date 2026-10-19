package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.operations.InstanceOperations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.operations.InstanceOperations.Operation;
import jakarta.inject.Inject;
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
import jakarta.ws.rs.HeaderParam;

/**
 * The operations API (WP-105d, docs/lifecycle.md, section 11). Every route acts on one instance, named in the path, and
 * takes its decision from the policy enforcement point through {@link InstanceOperations}.
 *
 * <ul>
 *   <li>{@code GET /operations/instances/{id}}: inspect (read-only).</li>
 *   <li>{@code POST .../cancel}, {@code .../retry}, {@code .../pause}, {@code .../recover}: mutating. They need an
 *       {@code Idempotency-Key} header (400 without it). A repeat of the same request returns the stored response.
 *       The same key for another request is 409.</li>
 * </ul>
 *
 * <p>Errors are RFC 9457 problem documents. 401 without a caller; 403 when the policy denies; 503 when the policy
 * decision (or the registry or audit trail) is unavailable, with nothing changed; 404 for an unknown, malformed or
 * other tenant's id; 409 for a state the operation does not apply to, a stale version, a key reused for another request
 * or a repair in progress; 501 for pause.
 */
@Path("/operations/instances/{id}")
@Produces(MediaType.APPLICATION_JSON)
public class OperationsResource {

    /** The header that makes a mutating call idempotent. */
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    @Inject
    CallerContextResolver callers;

    @Inject
    InstanceOperations operations;

    @GET
    public Response inspect(@Context HttpHeaders headers, @PathParam("id") String id) {
        return run(Operation.INSPECT, headers, id, null);
    }

    @POST
    @Path("/cancel")
    public Response cancel(@Context HttpHeaders headers, @PathParam("id") String id,
                           @HeaderParam(HEADER_IDEMPOTENCY_KEY) String key) {
        return run(Operation.CANCEL, headers, id, key);
    }

    @POST
    @Path("/retry")
    public Response retry(@Context HttpHeaders headers, @PathParam("id") String id,
                          @HeaderParam(HEADER_IDEMPOTENCY_KEY) String key) {
        return run(Operation.RETRY, headers, id, key);
    }

    @POST
    @Path("/pause")
    public Response pause(@Context HttpHeaders headers, @PathParam("id") String id,
                          @HeaderParam(HEADER_IDEMPOTENCY_KEY) String key) {
        return run(Operation.PAUSE, headers, id, key);
    }

    @POST
    @Path("/recover")
    public Response recover(@Context HttpHeaders headers, @PathParam("id") String id,
                            @HeaderParam(HEADER_IDEMPOTENCY_KEY) String key) {
        return run(Operation.RECOVER, headers, id, key);
    }

    private Response run(Operation operation, HttpHeaders headers, String id, String key) {
        CallerContext caller = callers.resolve(headers);
        Ulid instanceId = parseId(id);
        InstanceOperations.Outcome outcome = operations.perform(operation, caller, instanceId, key);
        return Response.status(outcome.status()).type(MediaType.APPLICATION_JSON).entity(outcome.body()).build();
    }

    private static Ulid parseId(String text) {
        try {
            return Ulid.parse(text);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException("no such instance", Response.Status.NOT_FOUND);
        }
    }
}
