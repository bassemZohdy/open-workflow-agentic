package io.github.bassemzohdy.openworkflow.spikec;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Bridge B on the agent side: the workflow's Java function calls this bean in the same JVM. The context arrives as the
 * {@link Ctx} argument; no HTTP and no JSON round trip happen between the task and the agent.
 */
@ApplicationScoped
@Unremovable
public class InProcessBridge {

    /** What a task hands to the next one. Plain fields, so the listen correlation can read instanceId. */
    public record Outcome(String instanceId, String threadId, String status) {
    }

    /** Input of the resume task: the approval event carries only the instance id. */
    public record Resume(String instanceId) {
    }

    @Inject
    ResearchGraph graph;

    @Inject
    Store store;

    public Outcome run(Ctx ctx) {
        var r = graph.run(ctx, "B-inprocess", null);
        return new Outcome(ctx.instanceId(), r.threadId(), r.status());
    }

    public Outcome resume(Resume in) {
        Ctx ctx = Ctx.fromJson((String) store.runByInstance(in.instanceId()).get("received_ctx"));
        var r = graph.resume(ctx);
        return new Outcome(ctx.instanceId(), r.threadId(), r.status());
    }
}
