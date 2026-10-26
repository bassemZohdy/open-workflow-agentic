package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.correlation.CorrelationIdFilter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
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
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.inject.Instance;
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
import java.util.Set;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Instance API (ADR 0019): start with an idempotency key, and read the registry. Reads never touch the engine's tables.
 *
 * <ul>
 *   <li>{@code POST /instances}: 202 with a Location for a new instance; 200 with the same body when the key was used
 *       before (nothing is started again); 409 when the key was used for a different request; 422 when the engine does
 *       not hold the pinned definition; 400 for a malformed body; 401 without a caller; 403 without the scope
 *       {@code oa.operate}; 503 when the policy decision or its audit record is unavailable.</li>
 *   <li>{@code GET /instances/{id}}: 200, or 404 for an unknown id or an id of another tenant; the scope
 *       {@code oa.inspect} is needed (403 without it, 503 when the decision is unavailable). The read stays tenant-wide
 *       for every caller who holds the scope.</li>
 *   <li>{@code POST /instances/{id}/reauthorize}: the scope {@code oa.operate} is needed as well as the exact starter,
 *       tenant and grant checks of the identity layer.</li>
 * </ul>
 *
 * <p>The order on every route is fixed and nothing is started, written, read or recovered before the last check has
 * passed:
 * <ol>
 *   <li>The caller is established ({@link CallerContextResolver}): 401 without a usable credential, 503 when the token
 *       cannot be checked. The body is not looked at before this.</li>
 *   <li>The policy enforcement point decides the route's own action ({@link Pep.InstanceApi}). DENY is 403, an
 *       unavailable decision (engine, timeout, bulkhead or audit failure) is 503. The routes are not safe-recovery
 *       operations, so there is no emergency fallback for them. The decision is audited.</li>
 *   <li>Only then the request is parsed (400, and 404 for a path that is no instance id) and looked up.</li>
 *   <li>The signed expiry of a verified token is checked again after the policy and audit (which block) and
 *       immediately before the first effect: the start, the authority write, the immediate recovery.</li>
 *   <li>It is checked once more after the response is built and immediately before it is returned. A caller whose
 *       authority ended meanwhile gets 401 (503 when that cannot be decided) and no instance view. The work itself may
 *       already have happened and is not undone: a started instance is found again with the same idempotency key.</li>
 * </ol>
 */
@Path("/instances")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InstanceResource {

    private static final Logger LOG = Logger.getLogger(InstanceResource.class);

    /** The scope that starts and re-authorizes an instance. */
    public static final String SCOPE_OPERATE = "oa.operate";
    /** The scope that reads an instance. */
    public static final String SCOPE_INSPECT = "oa.inspect";

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

    /** Resolved on first use, after the caller is known, so an anonymous request never builds the policy engine. */
    @Inject
    Instance<Pep> peps;

    @POST
    public Response start(@Context HttpHeaders headers, @Context UriInfo uri, StartBody body) {
        CallerAuthority who = callers.resolveAuthority(headers);
        CallerContext caller = who.caller();
        authorize(caller, Pep.InstanceApi.START, null);
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
        // policy, audit and the grant lookup above can block: the authority is judged again immediately before the start
        current(who, SCOPE_OPERATE, "nothing was started");
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
        Response response = Response.status(status).location(location).entity(view(row)).build();
        // the start blocked and its result is private: a refusal here may follow a real start (not undone; the same
        // idempotency key finds the instance again), only the view is withheld
        current(who, SCOPE_OPERATE, "the result was withheld; the instance may have been started");
        return response;
    }

    /**
     * Renews the delegated authority of an instance that waits for it (docs/identity.md section 5). Only the user who
     * started the instance may do it, with a new grant of their own to the same agent, and the caller needs the scope
     * {@code oa.operate}. 200 with the instance;
     * 404 for an unknown instance, an instance of another tenant or another user's instance; 403, 409 and 503 as in
     * {@link #problem}.
     */
    @POST
    @Path("/{id}/reauthorize")
    public Response reauthorize(@Context HttpHeaders headers, @PathParam("id") String id, ReauthorizeBody body) {
        CallerAuthority who = callers.resolveAuthority(headers);
        CallerContext caller = who.caller();
        Ulid instanceId = parseIdOrNull(id);
        authorize(caller, Pep.InstanceApi.REAUTHORIZE, instanceId);
        if (instanceId == null) {
            throw new WebApplicationException("no such instance", Response.Status.NOT_FOUND);
        }
        if (body == null) {
            throw new WebApplicationException("the request body is required", Response.Status.BAD_REQUEST);
        }
        Ulid grantId = parseGrant(body.delegationGrantId());
        if (grantId == null) {
            throw new WebApplicationException("delegationGrantId is required", Response.Status.BAD_REQUEST);
        }
        // policy and audit above can block: the authority is judged again immediately before the authority write
        current(who, SCOPE_OPERATE, "nothing was changed");
        InstanceAuthority renewed;
        try {
            renewed = authorities.reauthorize(caller.tenant(), caller.subject(), instanceId, grantId, correlation());
        } catch (GrantException e) {
            throw problem(e);
        }
        // The immediate resume is a further effect and waits for a caller whose authority is still current. When it is
        // not, the renewal stays recorded (it is not undone) and the next periodic pass resumes the instance.
        current(who, SCOPE_OPERATE, "the instance was re-authorized and is resumed by the next pass");
        // Resume now rather than at the next periodic pass. A failure here is not the caller's: the pass will retry.
        try {
            reconciler.recover(caller.tenant(), instanceId.value());
        } catch (RuntimeException e) {
            LOG.warnf("instance %s was re-authorized but could not be resumed at once (%s); the next pass resumes it",
                    instanceId, e.getClass().getSimpleName());
        }
        Optional<InstanceRecord> row = service.get(caller.tenant(), instanceId);
        LOG.infof("instance %s re-authorized under grant %s", renewed.instanceId(), renewed.grantId());
        Optional<Response> response = row.map(found -> Response.ok(view(found)).build());
        current(who, SCOPE_OPERATE, "the instance was re-authorized; the view was withheld");
        return response.orElseThrow(() -> new WebApplicationException("no such instance", Response.Status.NOT_FOUND));
    }

    @GET
    @Path("/{id}")
    public InstanceView get(@Context HttpHeaders headers, @PathParam("id") String id) {
        CallerAuthority who = callers.resolveAuthority(headers);
        CallerContext caller = who.caller();
        Ulid instanceId = parseIdOrNull(id);
        authorize(caller, Pep.InstanceApi.GET, instanceId);
        if (instanceId == null) {
            throw new WebApplicationException("no such instance", Response.Status.NOT_FOUND);
        }
        current(who, SCOPE_INSPECT, "nothing was read");
        Optional<InstanceRecord> row = service.get(caller.tenant(), instanceId);
        Optional<InstanceView> output = row.map(InstanceResource::view);
        // read by blocking I/O: neither the view nor the existence of the row (a 404 is a signal too) leaves after the
        // identity expired, so this check comes before the missing row is turned into an answer
        current(who, SCOPE_INSPECT, "the instance view was withheld");
        return output.orElseThrow(() -> new WebApplicationException("no such instance", Response.Status.NOT_FOUND));
    }

    /**
     * Asks the policy enforcement point about the route. A caller that was built by hand or has no subject is refused.
     * DENY is 403; ERROR, a failing engine or audit, and a null decision are 503. Scopes that cannot form a policy
     * context are 403, not a server error.
     */
    private void authorize(CallerContext caller, Pep.InstanceApi route, Ulid instanceId) {
        if (caller == null || caller.provenance() == CallerContext.Provenance.UNVERIFIED || caller.subject() == null) {
            throw new ProblemException(Response.Status.FORBIDDEN, "the caller is not authorized for this request");
        }
        Decision decision;
        try {
            Pep pep = peps.get();
            decision = pep.instanceApi(route, caller.subject(), caller.scopes(), instanceId);
        } catch (IllegalArgumentException invalidScopes) {
            throw new ProblemException(Response.Status.FORBIDDEN, "the caller is not authorized for this request");
        } catch (RuntimeException unavailable) {
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the policy decision is unavailable; nothing was done");
        }
        if (decision == null || decision.outcome() == DecisionOutcome.ERROR) {
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "the policy decision is unavailable; nothing was done");
        }
        if (!decision.allows()) {
            throw new ProblemException(Response.Status.FORBIDDEN, "the caller is not authorized for this request");
        }
    }

    /**
     * Shows that the caller's signed authority is current now: 401 when the token has expired or the authority is gone,
     * 503 when that cannot be decided. The development-header seam has no signed expiry and keeps its explicit dev and
     * test seam; the resolver refuses it outside dev and test profiles.
     */
    private static void current(CallerAuthority who, String scope, String consequence) {
        if (!who.caller().verified()) {
            return;
        }
        try {
            who.requireCurrent(Set.of(scope));
        } catch (AuthorityLostException gone) {
            if (gone.reason() == AuthorityLostException.Reason.UNAVAILABLE) {
                throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                        "the authority could not be checked; " + consequence);
            }
            throw new WebApplicationException("the caller's authority is no longer valid", Response
                    .status(Response.Status.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"").build());
        }
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

    /** The instance id in the path, or null when the text is no instance id (the route answers 404 after the policy). */
    private static Ulid parseIdOrNull(String text) {
        try {
            return Ulid.parse(text);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    static InstanceView view(InstanceRecord row) {
        return new InstanceView(row.id().value(), row.definition().definitionId(), row.definition().version(),
                row.status().name(), row.engineInstanceId(), row.startAttempts(), row.version(), row.createdAt(),
                row.updatedAt());
    }
}
