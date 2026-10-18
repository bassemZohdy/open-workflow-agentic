package io.github.bassemzohdy.openworkflow.spike;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Callback endpoints used by the OWS definitions (call: http) and by the tests.
 * Every probe row records the X-Flow-* headers Quarkus Flow attaches to HTTP task calls.
 */
@ApplicationScoped
@Path("/spike")
public class ProbeResource {

    private static final Map<String, AtomicInteger> FLAKY_CALLS = new ConcurrentHashMap<>();

    @Inject
    DataSource dataSource;

    /**
     * Returns 200 with a JSON body on purpose: an empty 204 response cannot be mapped by the Flow HTTP client
     * ("could not be mapped to type JsonNode"), which fails the task (see findings).
     */
    @POST
    @Path("/probe/{tag}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response probe(@PathParam("tag") String tag,
            @HeaderParam("X-Flow-Instance-Id") String instanceId,
            @HeaderParam("X-Flow-Task-Id") String taskId,
            @HeaderParam("X-Correlation-Id") String correlation,
            @Context HttpHeaders headers,
            String body) {
        insert(tag, instanceId, taskId, correlation, "headers=" + headerNames(headers) + " body=" + body);
        return Response.ok(Map.of("ok", true)).build();
    }

    @GET
    @Path("/probe/{tag}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> rows(@PathParam("tag") String tag, @QueryParam("instanceId") String instanceId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "select tag, instance_id, task_id, correlation, detail, "
                + "extract(epoch from created_at) * 1000 as at_ms from spike_probe where tag = ? "
                + (instanceId == null ? "" : "and instance_id = ? ") + "order by id";
        try (var c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tag);
            if (instanceId != null) {
                ps.setString(2, instanceId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tag", rs.getString(1));
                    row.put("instanceId", rs.getString(2));
                    row.put("taskId", rs.getString(3));
                    row.put("correlation", rs.getString(4));
                    row.put("detail", rs.getString(5));
                    row.put("atMs", rs.getDouble(6));
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return Map.of("count", rows.size(), "rows", rows);
    }

    /** Slow endpoint for deadline tests. */
    @GET
    @Path("/slow")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> slow(@QueryParam("ms") long ms) throws InterruptedException {
        Thread.sleep(ms);
        return Map.of("sleptMs", ms);
    }

    /** Side effect that is recorded before a slow tail, so a kill -9 can land while the call is in flight. */
    @POST
    @Path("/effect")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> effect(@HeaderParam("X-Flow-Instance-Id") String instanceId,
            @HeaderParam("X-Flow-Task-Id") String taskId, String body) throws InterruptedException {
        insert("effect", instanceId, taskId, null, "started body=" + body);
        Thread.sleep(4000);
        return Map.of("ok", true);
    }

    /** Fails with HTTP 500 on the first two calls for a tag, then succeeds. */
    @GET
    @Path("/flaky/{tag}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response flaky(@PathParam("tag") String tag) {
        int attempt = FLAKY_CALLS.computeIfAbsent("get:" + tag, k -> new AtomicInteger()).incrementAndGet();
        if (attempt <= 2) {
            return Response.serverError().entity(Map.of("attempt", attempt)).build();
        }
        return Response.ok(Map.of("attempt", attempt)).build();
    }

    /** Number of requests this endpoint has received for a tag (GET or POST variant). */
    @GET
    @Path("/flaky/{tag}/attempts")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> attempts(@PathParam("tag") String tag) {
        return Map.of("get", FLAKY_CALLS.getOrDefault("get:" + tag, new AtomicInteger()).get(),
                "post", FLAKY_CALLS.getOrDefault("post:" + tag, new AtomicInteger()).get());
    }

    /** POST variant of the flaky endpoint: a non-idempotent call that fails twice before succeeding (Q08). */
    @POST
    @Path("/flaky-post/{tag}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response flakyPost(@PathParam("tag") String tag, String body) {
        int attempt = FLAKY_CALLS.computeIfAbsent("post:" + tag, k -> new AtomicInteger()).incrementAndGet();
        insert("flaky-post", null, null, null, "attempt=" + attempt + " body=" + body);
        if (attempt <= 2) {
            return Response.serverError().entity(Map.of("attempt", attempt)).build();
        }
        return Response.ok(Map.of("attempt", attempt)).build();
    }

    private void insert(String tag, String instanceId, String taskId, String correlation, String detail) {
        String sql = "insert into spike_probe (tag, instance_id, task_id, correlation, detail) values (?,?,?,?,?)";
        try (var c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tag);
            ps.setString(2, instanceId);
            ps.setString(3, taskId);
            ps.setString(4, correlation);
            ps.setString(5, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> headerNames(HttpHeaders headers) {
        List<String> names = new ArrayList<>();
        headers.getRequestHeaders().keySet().forEach(k -> {
            if (k.toLowerCase().startsWith("x-") || k.equalsIgnoreCase("authorization")) {
                names.add(k);
            }
        });
        return names;
    }
}
