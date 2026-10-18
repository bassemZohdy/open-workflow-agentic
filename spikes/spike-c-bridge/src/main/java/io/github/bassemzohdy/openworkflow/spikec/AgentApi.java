package io.github.bassemzohdy.openworkflow.spikec;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * The agent's entry points. Bridge A calls {@code /run} and {@code /resume} synchronously. Bridge C calls
 * {@code /start} and {@code /resume-async} and receives callbacks. Bridge B has no endpoint: it calls
 * {@link ResearchGraph} in process.
 */
@Path("/agents/research")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AgentApi {

    @Inject
    ResearchGraph graph;

    @Inject
    Store store;

    @Inject
    AsyncAgent async;

    /** Bridge A: runs until the approval interrupt or the end. The HTTP call returns at the interrupt. */
    @POST
    @Path("/run")
    public Map<String, Object> run(@HeaderParam("X-Correlation-Id") String correlationHeader, Ctx ctx) {
        var r = graph.run(ctx, "A-http", correlationHeader);
        return Map.of("instanceId", ctx.instanceId(), "threadId", r.threadId(), "status", r.status());
    }

    /** Bridge A: resumes after approval. Only the instance id arrives; the context comes from the run record. */
    @POST
    @Path("/resume")
    public Map<String, Object> resume(Map<String, String> body) {
        Ctx ctx = contextOf(body.get("instanceId"));
        var r = graph.resume(ctx);
        return Map.of("instanceId", ctx.instanceId(), "threadId", r.threadId(), "status", r.status());
    }

    /** Bridge C: accepts the run and returns 202. The result comes back as a callback event. */
    @POST
    @Path("/start")
    public Response start(@HeaderParam("X-Correlation-Id") String correlationHeader,
            @QueryParam("early") boolean early, Ctx ctx) {
        async.start(ctx, correlationHeader, early);
        return Response.accepted(Map.of("instanceId", ctx.instanceId(), "threadId", ctx.threadId().value(),
                "status", "ACCEPTED")).build();
    }

    /** Bridge C: accepts the resume and returns 202. The completion comes back as a callback event. */
    @POST
    @Path("/resume-async")
    public Response resumeAsync(Map<String, String> body) {
        Ctx ctx = contextOf(body.get("instanceId"));
        async.resume(ctx);
        return Response.accepted(Map.of("instanceId", ctx.instanceId(), "threadId", ctx.threadId().value(),
                "status", "ACCEPTED")).build();
    }

    /** Bridge C: the workflow acknowledges a callback after its listen consumed it. */
    @POST
    @Path("/acks")
    public Response acknowledge(Map<String, String> body) {
        async.acknowledge(body.get("instanceId"), body.get("type"));
        // A JSON body: the Flow HTTP task cannot map an empty response (observed, WP-005).
        return Response.accepted(Map.of("acknowledged", true)).build();
    }

    private Ctx contextOf(String instanceId) {
        Map<String, Object> row = store.runByInstance(instanceId);
        if (row == null) {
            throw new jakarta.ws.rs.NotFoundException("no agent run for instance " + instanceId);
        }
        return Ctx.fromJson((String) row.get("received_ctx"));
    }
}
