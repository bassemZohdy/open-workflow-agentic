package io.github.bassemzohdy.openworkflow.spikec;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge C on the agent side. Runs the graph on a background thread and reports back with callback CloudEvents.
 *
 * <p>A callback is written to the outbox ({@code spikec.agent_outbox}) before it is published, and it is
 * republished every {@code spikec.c.redeliver-ms} until the workflow acknowledges it. Without this, a callback sent
 * before the workflow's listen is registered is lost (WP-005 Q5: 3 of 10 plain runs lost it). With
 * {@code spikec.c.redeliver=false} the plain behaviour is kept for the characterization test.
 */
@ApplicationScoped
public class AsyncAgent {

    static final String STARTED = "spikec.agent.started";
    static final String COMPLETED = "spikec.agent.completed";

    private static final Logger LOG = LoggerFactory.getLogger(AsyncAgent.class);

    @Inject
    ResearchGraph graph;

    @Inject
    Store store;

    @Inject
    Events events;

    /** Test switch: send the start callback before the start request returns (WP-005 race, Q5). */
    @ConfigProperty(name = "spikec.c.early-callback", defaultValue = "false")
    boolean earlyCallback;

    @ConfigProperty(name = "spikec.c.redeliver", defaultValue = "true")
    boolean redeliver;

    @ConfigProperty(name = "spikec.c.redeliver-ms", defaultValue = "300")
    long redeliverMs;

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "spikec-agent");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService outboxTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spikec-outbox");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void startOutbox() {
        if (redeliver) {
            outboxTimer.scheduleWithFixedDelay(this::redeliverPending, redeliverMs, redeliverMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Starts a run. The start callback is sent when the run reaches its approval interrupt. */
    public void start(Ctx ctx, String headerCorrelation, boolean early) {
        if (early || earlyCallback) {
            var r = graph.run(ctx, "C-async", headerCorrelation);
            callback(ctx, STARTED, r.status());
            return;
        }
        pool.submit(() -> {
            var r = graph.run(ctx, "C-async", headerCorrelation);
            callback(ctx, STARTED, r.status());
        });
    }

    public void resume(Ctx ctx) {
        store.setRunStatus(ctx.threadId().value(), "RESUMING");
        pool.submit(() -> {
            var r = graph.resume(ctx);
            callback(ctx, COMPLETED, r.status());
        });
    }

    /** Reconciler entry: finishes a run that a kill -9 interrupted in flight. */
    public void recover(Ctx ctx, String status) {
        if ("RESUMING".equals(status)) {
            resume(ctx);
        } else {
            start(ctx, null, false);
        }
    }

    /** Workflow acknowledgement: the listen consumed the callback, stop redelivering it. */
    public void acknowledge(String instanceId, String type) {
        store.outboxAck(instanceId, type);
    }

    private void callback(Ctx ctx, String type, String status) {
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(Map.of(
                    "instanceId", ctx.instanceId(),
                    "threadId", ctx.threadId().value(),
                    "status", status));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (!redeliver) {
            events.publish(type, json, ctx.correlationId());
            return;
        }
        String eventId = UUID.randomUUID().toString();
        store.outboxAdd(eventId, ctx.instanceId(), type, json);
        events.publish(eventId, type, json, ctx.correlationId());
    }

    private void redeliverPending() {
        try {
            for (Map<String, Object> row : store.outboxPending()) {
                events.publish((String) row.get("event_id"), (String) row.get("event_type"),
                        (String) row.get("payload"), null);
            }
        } catch (RuntimeException e) {
            LOG.debug("outbox redelivery skipped: {}", e.getMessage());
        }
    }
}
