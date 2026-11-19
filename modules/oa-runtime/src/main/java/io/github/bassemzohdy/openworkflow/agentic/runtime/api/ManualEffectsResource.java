package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.ManualEffectOperations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/** Verified-human operator API. Public identifiers are fingerprints, never raw effect keys or private target data. */
@Path("/operations/instances/{id}/effects")
@Produces(MediaType.APPLICATION_JSON)
public class ManualEffectsResource {
    private static final Logger LOG = Logger.getLogger(ManualEffectsResource.class);
    public record ResolutionBody(EffectState outcome, String reason) { }
    @Inject CallerContextResolver callers;
    @Inject ManualEffectOperations operations;

    @GET
    @Path("/unknown")
    public ManualEffectOperations.Page unknown(@Context HttpHeaders headers, @PathParam("id") String id,
            @QueryParam("cursor") String cursor, @QueryParam("limit") Integer limit) {
        return guarded(() -> {
            var authority = callers.resolveAuthority(headers);
            operations.requireEnabled();
            return operations.unknown(authority, instance(id), PageRequest.of(cursor, limit));
        });
    }

    @POST
    @Path("/{effectId}/resolve")
    @Consumes(MediaType.APPLICATION_JSON)
    public ManualEffectOperations.Resolution resolve(@Context HttpHeaders headers, @PathParam("id") String id,
            @PathParam("effectId") String effectId, @HeaderParam("Idempotency-Key") String key, ResolutionBody body) {
        return guarded(() -> {
            var authority = callers.resolveAuthority(headers);
            operations.requireEnabled();
            if (body == null) throw new IllegalArgumentException("A resolution body is required.");
            Ulid request;
            try { request = Ulid.parse(key); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Idempotency-Key must be a ULID."); }
            return operations.resolve(authority, instance(id), effectId, request, body.outcome(), body.reason());
        });
    }

    private static Ulid instance(String id) {
        try { return Ulid.parse(id); }
        catch (RuntimeException invalid) { throw new ProblemException(Response.Status.NOT_FOUND, "No such instance."); }
    }

    private static <T> T guarded(Supplier<T> operation) {
        try { return operation.get(); }
        catch (WebApplicationException known) { throw known; }
        catch (AuthorityLostException lost) {
            if (lost.reason() == AuthorityLostException.Reason.UNAVAILABLE)
                throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE, "Operator authority could not be checked.");
            if (lost.reason() == AuthorityLostException.Reason.EXPIRED || lost.reason() == AuthorityLostException.Reason.NOT_VERIFIED)
                throw new WebApplicationException("Current verified operator authority is required.",
                        Response.status(401).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build());
            throw new ProblemException(Response.Status.FORBIDDEN, "Operator authority does not permit this operation.");
        } catch (PolicyDenied denied) {
            throw new ProblemException(denied.decision().outcome() == DecisionOutcome.ERROR ? 503 : 403,
                    "Policy did not allow manual effect recovery.");
        } catch (IllegalArgumentException invalid) {
            throw new ProblemException(Response.Status.BAD_REQUEST, "Invalid manual effect request.");
        } catch (IllegalStateException conflict) {
            throw new ProblemException(Response.Status.CONFLICT,
                    "The resolution conflicts with retained state. Inspect it and reuse the same request id when retrying.");
        } catch (RuntimeException unavailable) {
            // No request body, key, target, database message or credential is included in the log or response.
            LOG.warnf("manual effect operation unavailable; failure=%s", unavailable.getClass().getName());
            throw new ProblemException(Response.Status.SERVICE_UNAVAILABLE,
                    "Resolution state could not be confirmed. Inspect it and reuse the same request id when retrying.");
        }
    }
}
