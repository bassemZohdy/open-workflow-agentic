package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.correlation.CorrelationIdFilter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GrantException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthorities;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionNotFoundException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.IdempotencyKeyReusedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Reconciler;
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
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

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

    private static final Logger LOG = Logger.getLogger(InstanceResource.class);

    /**
     * The body of a start request. {@code delegationGrantId} is optional: the grant the instance runs under (a grant the
     * caller gave to an agent). Without it the instance carries no delegated authority.
     */
    public record StartBody(String definitionId, String definitionVersion, Map<String, Object> input,
                            String idempotencyKey, String delegationGrantId) {
    }

    /** The body of a re-authorization: the new grant of the same user to the same agent. */
    public record ReauthorizeBody(String delegationGrantId) {
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

    @Inject
    InstanceAuthorities authorities;

    @Inject
    Reconciler reconciler;

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
        InstanceStore.InsertHook authority = bind(caller, body.delegationGrantId());
        StartResult result;
        try {
            result = service.start(request, authority);
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

    /**
     * Renews the delegated authority of an instance that waits for it (docs/identity.md section 5). Only the user who
     * started the instance may do it, with a new grant of their own to the same agent. 200 with the instance;
     * 404 for an unknown instance, an instance of another tenant or another user's instance; 403, 409 and 503 as in
     * {@link #problem}.
     */
    @POST
    @Path("/{id}/reauthorize")
    public Response reauthorize(@Context HttpHeaders headers, @PathParam("id") String id, ReauthorizeBody body) {
        CallerContext caller = callers.resolve(headers);
        Ulid instanceId = parseId(id);
        if (body == null) {
            throw new WebApplicationException("the request body is required", Response.Status.BAD_REQUEST);
        }
        Ulid grantId = parseGrant(body.delegationGrantId());
        if (grantId == null) {
            throw new WebApplicationException("delegationGrantId is required", Response.Status.BAD_REQUEST);
        }
        InstanceAuthority renewed;
        try {
            renewed = authorities.reauthorize(caller.tenant(), caller.subject(), instanceId, grantId, correlation());
        } catch (GrantException e) {
            throw problem(e);
        }
        // Resume now rather than at the next periodic pass. A failure here is not the caller's: the pass will retry.
        try {
            reconciler.recover(caller.tenant(), instanceId.value());
        } catch (RuntimeException e) {
            LOG.warnf("instance %s was re-authorized but could not be resumed at once (%s); the next pass resumes it",
                    instanceId, e.getClass().getSimpleName());
        }
        InstanceRecord row = service.get(caller.tenant(), instanceId).orElseThrow(
                () -> new WebApplicationException("no such instance", Response.Status.NOT_FOUND));
        LOG.infof("instance %s re-authorized under grant %s", renewed.instanceId(), renewed.grantId());
        return Response.ok(view(row)).build();
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

    /** Validates the grant of a start request and prepares the identity row for the insert transaction. */
    private InstanceStore.InsertHook bind(CallerContext caller, String grantText) {
        Ulid grantId = parseGrant(grantText);
        if (grantText != null && !grantText.isBlank() && grantId == null) {
            throw new WebApplicationException("delegationGrantId is not a grant id", Response.Status.BAD_REQUEST);
        }
        try {
            return authorities.bindAtStart(caller.tenant(), caller.subject(), grantId, correlation());
        } catch (GrantException e) {
            throw problem(e);
        }
    }

    private static Ulid parseGrant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Ulid.parse(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String correlation() {
        Object raw = MDC.get(CorrelationIdFilter.MDC_KEY);
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /** NOT_FOUND 404, FORBIDDEN 403, INACTIVE and INVALID_REQUEST 409, UNAVAILABLE 503; the message names no grant detail. */
    private static WebApplicationException problem(GrantException e) {
        Response.Status status = switch (e.code()) {
            case NOT_FOUND -> Response.Status.NOT_FOUND;
            case FORBIDDEN -> Response.Status.FORBIDDEN;
            case INACTIVE, INVALID_REQUEST, PARENT_INACTIVE, CYCLE -> Response.Status.CONFLICT;
            case UNAVAILABLE -> Response.Status.SERVICE_UNAVAILABLE;
        };
        return new WebApplicationException(e.getMessage(), status);
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
