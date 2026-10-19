package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.RegistryScan;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Dev and test endpoints for the lifecycle process tests (WP-105c). They answer 404 unless
 * {@code agentic.dev.lifecycle-probes=true}, which must never be set in a deployment.
 *
 * <ul>
 *   <li>{@code POST /dev/lifecycle/effects}: the HTTP "effect" call of the lifecycle-probe definition. It logs the
 *       engine instance id (from the {@code X-Flow-Instance-Id} header Flow sends), the resume epoch the registry holds
 *       for that instance, and the mode. Mode {@code fail} answers 500. Mode {@code hold} sleeps on epoch 0 only, so a
 *       test can kill the process while the effect is in flight.</li>
 *   <li>{@code GET /dev/lifecycle/effects}: the calls this process received, for in-process tests.</li>
 *   <li>{@code POST /dev/lifecycle/events}: publishes a CloudEvent to the engine's event publishers, which wakes the
 *       listen task of the lifecycle-probe definition.</li>
 * </ul>
 */
@Path("/dev/lifecycle")
@Produces(MediaType.APPLICATION_JSON)
public class DevLifecycleResource {

    private static final Logger LOG = Logger.getLogger(DevLifecycleResource.class);
    private static final long HOLD_MILLIS = 20_000;

    /** One effect call as this process received it. */
    public record EffectCall(String instanceId, long epoch, String mode, int status) {
    }

    private static final List<EffectCall> CALLS = new CopyOnWriteArrayList<>();

    @ConfigProperty(name = "agentic.dev.lifecycle-probes", defaultValue = "false")
    boolean enabled;

    @Inject
    WorkflowApplication application;

    @Inject
    RegistryScan scan;

    @POST
    @Path("/effects")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response effect(@Context HttpHeaders headers, Map<String, Object> body) {
        if (!enabled) {
            return Response.status(404).build();
        }
        String instanceId = headers.getHeaderString("X-Flow-Instance-Id");
        String mode = String.valueOf(body == null ? "ok" : body.getOrDefault("mode", "ok"));
        long epoch = instanceId == null ? -1 : scan.resumeEpochOfEngineInstance(instanceId).orElse(-1);
        LOG.infof("probe-effect received instance=%s epoch=%d mode=%s", instanceId, epoch, mode);
        if ("hold".equals(mode) && epoch == 0) {
            sleep(HOLD_MILLIS);
        }
        int status = "fail".equals(mode) ? 500 : 200;
        LOG.infof("probe-effect instance=%s epoch=%d mode=%s status=%d", instanceId, epoch, mode, status);
        CALLS.add(new EffectCall(instanceId, epoch, mode, status));
        return Response.status(status).entity(Map.of("epoch", epoch, "mode", mode)).build();
    }

    @GET
    @Path("/effects")
    public Response calls() {
        if (!enabled) {
            return Response.status(404).build();
        }
        return Response.ok(new ArrayList<>(CALLS)).build();
    }

    @POST
    @Path("/events")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response publish(Map<String, Object> body) {
        if (!enabled) {
            return Response.status(404).build();
        }
        String type = String.valueOf(body == null ? "" : body.getOrDefault("type", ""));
        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("urn:agentic:dev"))
                .withType(type)
                .withDataContentType(MediaType.APPLICATION_JSON)
                .withData("{}".getBytes(StandardCharsets.UTF_8))
                .build();
        application.eventPublishers().forEach(publisher -> publisher.publish(event));
        return Response.accepted(Map.of("type", type)).build();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
