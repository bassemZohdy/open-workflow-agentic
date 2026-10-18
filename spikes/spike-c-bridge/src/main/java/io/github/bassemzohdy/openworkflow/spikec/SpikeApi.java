package io.github.bassemzohdy.openworkflow.spikec;

import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import io.serverlessworkflow.impl.WorkflowInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The spike's own instance API (ADR 0019 shape): start writes a registry row before the engine starts, query reads
 * the registry and the agent record, approval publishes the approval event.
 */
@ApplicationScoped
@Path("/spikec")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SpikeApi {

    public record StartBody(String tenant, String user, String correlationId, String deadline, int budget,
            String topic) {
    }

    public record Approval(String instanceId) {
    }

    @Inject
    WorkflowApplication application;

    @Inject
    Store store;

    @Inject
    Events events;

    @Inject
    Lifecycle lifecycle;

    @GET
    @Path("/ready")
    public Response ready() {
        return lifecycle.isReady() ? Response.ok(Map.of("ready", true)).build()
                : Response.status(503).entity(Map.of("ready", false)).build();
    }

    @POST
    @Path("/instances/{bridge}")
    public Response start(@PathParam("bridge") String bridge, StartBody body) {
        if (!lifecycle.isReady()) {
            return Response.status(503).build();
        }
        String definitionName = switch (bridge) {
            case "a", "b", "c" -> "bridge-" + bridge;
            default -> null;
        };
        if (definitionName == null) {
            return Response.status(404).entity(Map.of("error", "unknown bridge " + bridge)).build();
        }
        Ctx ctx = new Ctx(body.tenant(), body.user(), UUID.randomUUID().toString(), body.correlationId(),
                body.deadline(), body.budget(), body.topic());
        // 1. registry row first, so a crash after this point is visible to the reconciler
        store.insertInstance(bridge, ctx, "STARTING");
        WorkflowDefinition definition = definition(definitionName);
        // 2. create the engine instance (its id is known before start), record it, then start
        WorkflowInstance instance = definition.instance(ctx.asMap());
        store.setEngineId(ctx.instanceId(), instance.id());
        instance.start();
        // 3. running
        store.setInstanceStatus(ctx.instanceId(), "RUNNING");
        return Response.accepted(Map.of("instanceId", ctx.instanceId(), "threadId", ctx.threadId().value(),
                "engineId", instance.id())).build();
    }

    @GET
    @Path("/instances/{id}")
    public Response query(@PathParam("id") String id) {
        Map<String, Object> row = store.instance(id);
        if (row == null) {
            return Response.status(404).build();
        }
        String thread = (String) row.get("thread_id");
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("run", store.run(thread));
        out.put("steps", store.steps(thread));
        out.put("effects", store.effectCount(thread));
        out.put("engineStatus", engineStatus((String) row.get("bridge"), (String) row.get("engine_id")));
        out.put("lifecycle", Observer.of((String) row.get("engine_id")));
        return Response.ok(out).build();
    }

    @POST
    @Path("/approvals")
    public Response approve(Approval approval) {
        events.publish(InProcessFlow.APPROVAL, "{\"instanceId\":\"" + approval.instanceId() + "\"}", null);
        return Response.accepted(Map.of("instanceId", approval.instanceId())).build();
    }

    @GET
    @Path("/effects")
    public Map<String, Object> effects() {
        return Map.of("count", store.effectCountAll());
    }

    private String engineStatus(String bridge, String engineId) {
        if (engineId == null) {
            return "not-started";
        }
        WorkflowDefinition d = definition("bridge-" + bridge);
        return d.activeInstance(engineId).map(i -> i.status().name()).orElse("not-active");
    }

    private WorkflowDefinition definition(String name) {
        WorkflowDefinition d = application.workflowDefinitions().get(new WorkflowDefinitionId("spikec", name, "1.0.0"));
        if (d == null) {
            throw new jakarta.ws.rs.NotFoundException("no definition " + name);
        }
        return d;
    }
}
