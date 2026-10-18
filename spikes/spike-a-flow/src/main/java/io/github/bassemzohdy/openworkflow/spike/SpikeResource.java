package io.github.bassemzohdy.openworkflow.spike;

import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The instance API this spike proposes. Quarkus Flow ships no REST resource for instances,
 * so start, query and cancel are written here on top of the engine API (see findings Q01, Q05).
 */
@ApplicationScoped
@Path("/spike")
@Produces(MediaType.APPLICATION_JSON)
public class SpikeResource {

    public record StartRequest(String namespace, String name, String version, Map<String, Object> input) {}

    public record EventRequest(String type, String data) {}

    @Inject
    WorkflowApplication application;

    @Inject
    PersistenceInstanceHandlers handlers;

    @Inject
    JpaInstanceOperations rawOperations;

    @POST
    @Path("/instances")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response start(StartRequest request) {
        WorkflowDefinition definition = definition(request.namespace(), request.name(), request.version());
        if (definition == null) {
            return Response.status(404).entity(Map.of("error", "no such definition")).build();
        }
        WorkflowInstance instance = definition.instance(request.input());
        // start() returns a future that completes when the instance completes or waits; the HTTP call does not wait for it.
        instance.start();
        return Response.accepted(Map.of("id", instance.id(), "status", String.valueOf(instance.status()))).build();
    }

    @GET
    @Path("/instances/{id}")
    public Response query(@PathParam("id") String id, @QueryParam("namespace") String namespace,
            @QueryParam("name") String name, @QueryParam("version") String version) {
        WorkflowDefinition definition = definition(namespace, name, version);
        if (definition == null) {
            return Response.status(404).entity(Map.of("error", "no such definition")).build();
        }
        var active = definition.activeInstance(id);
        if (active.isPresent()) {
            return Response.ok(Map.of("id", id, "source", "active", "status", active.get().status().name())).build();
        }
        // Not in memory: read the persisted row. Note the side effect checked in Q05: reading registers an instance object.
        var raw = rawOperations.readWorkflowInfo(definition, id);
        if (raw.isEmpty()) {
            return Response.status(404).entity(Map.of("id", id, "source", "none")).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("source", "persisted");
        body.put("status", String.valueOf(raw.get().status()));
        body.put("completedTaskPointers", raw.get().tasks().keySet().stream().sorted().toList());
        return Response.ok(body).build();
    }

    /**
     * Resume a persisted instance that the engine registered as active but did not start (the restore gap, Q02).
     * Calls start() on the in-memory object; a WAITING listen re-registers, a PENDING one runs from its first
     * unfinished task. Persisted completed tasks are skipped by restoreContext.
     */
    @POST
    @Path("/instances/{id}/resume")
    public Response resume(@PathParam("id") String id, @QueryParam("namespace") String namespace,
            @QueryParam("name") String name, @QueryParam("version") String version) {
        WorkflowDefinition definition = definition(namespace, name, version);
        if (definition == null) {
            return Response.status(404).entity(Map.of("error", "no such definition")).build();
        }
        var instance = definition.activeInstance(id);
        if (instance.isEmpty()) {
            return Response.status(404).entity(Map.of("id", id, "error", "not active in memory")).build();
        }
        instance.get().start();
        return Response.accepted(Map.of("id", id, "status", String.valueOf(instance.get().status()))).build();
    }

    @POST
    @Path("/instances/{id}/cancel")
    public Response cancel(@PathParam("id") String id, @QueryParam("namespace") String namespace,
            @QueryParam("name") String name, @QueryParam("version") String version) {
        WorkflowDefinition definition = definition(namespace, name, version);
        if (definition == null) {
            return Response.status(404).entity(Map.of("error", "no such definition")).build();
        }
        var active = definition.activeInstance(id);
        if (active.isEmpty()) {
            return Response.status(404).entity(Map.of("id", id, "error", "not active in memory")).build();
        }
        boolean changed = active.get().cancel();
        return Response.ok(Map.of("id", id, "cancelled", changed)).build();
    }

    @GET
    @Path("/definitions")
    public Map<String, Object> definitions() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (WorkflowDefinition d : application.workflowDefinitions().values()) {
            out.put(d.id().namespace() + ":" + d.id().name() + ":" + d.id().version(),
                    d.activeInstances().stream().map(WorkflowInstance::id).toList());
        }
        return out;
    }

    @POST
    @Path("/events")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response publish(EventRequest request) {
        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("urn:spike:api"))
                .withType(request.type())
                .withDataContentType(MediaType.APPLICATION_JSON)
                .withData((request.data() == null ? "{}" : request.data()).getBytes(StandardCharsets.UTF_8))
                .build();
        application.eventPublishers().forEach(p -> p.publish(event));
        return Response.accepted().build();
    }

    @GET
    @Path("/observed/{id}")
    public List<String> observed(@PathParam("id") String id) {
        return ObservingListener.eventsOf(id);
    }

    @DELETE
    @Path("/observed")
    public Response clearObserved() {
        ObservingListener.clear();
        return Response.noContent().build();
    }

    private WorkflowDefinition definition(String namespace, String name, String version) {
        return application.workflowDefinitions().get(new WorkflowDefinitionId(namespace, name, version));
    }
}
