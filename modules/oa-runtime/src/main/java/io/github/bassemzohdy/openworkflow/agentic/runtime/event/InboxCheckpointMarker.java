package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.serverlessworkflow.impl.TaskContextData;
import io.serverlessworkflow.impl.WorkflowContext;
import io.serverlessworkflow.impl.WorkflowContextData;
import java.time.Duration;
import java.util.*;

/** Trusted runtime metadata, installed after audited reservation and before invoking the SDK callback. No payload/token. */
public record InboxCheckpointMarker(InboxWait occurrence, String receiptFingerprint, int attempt) {
    static final String PREFIX = "oa.inbox.checkpoint.";

    public InboxCheckpointMarker {
        Objects.requireNonNull(occurrence);
        if (receiptFingerprint == null || !receiptFingerprint.matches("[0-9a-f]{64}") || attempt < 1) {
            throw new IllegalArgumentException("invalid dispatch checkpoint marker");
        }
    }

    public static void install(WorkflowContext workflow, TaskContextData task, JdbcInboxDispatch.Reserved reserved) {
        InboxWait wait = reserved.occurrence();
        if (!reserved.receipt().tenant().equals(wait.tenant()) || !reserved.receipt().instanceId().equals(wait.instanceId())
                || !reserved.receipt().correlationKey().equals(wait.key()) || !reserved.receipt().type().equals(wait.eventType())) {
            throw new IllegalArgumentException("reservation receipt does not identify this occurrence");
        }
        var marker = new InboxCheckpointMarker(wait, reserved.receipt().fingerprint(), reserved.attempt());
        marker.requireCheckpoint(workflow,task);
        String key = PREFIX + wait.digest();
        // A restart/replay may replace an older attempt of the SAME immutable receipt. The DB fence verifies the attempt.
        workflow.instance().removeMetadata(key);
        workflow.instance().addMetadataIfAbsent(key, marker::asMetadata);
    }

    void requireCheckpoint(WorkflowContextData workflow, TaskContextData task) {
        var id = workflow.definition().id();
        InboxWait w = occurrence;
        if (!w.applicationId().equals(workflow.definition().application().id())
                || !w.engineInstanceId().equals(workflow.instanceData().id())
                || !w.namespace().equals(id.namespace()) || !w.definition().equals(id.name()) || !w.version().equals(id.version())
                || !w.taskPosition().equals(task.position().jsonPointer()) || w.iteration() != task.iteration()) {
            throw new IllegalStateException("checkpoint does not identify the reserved occurrence");
        }
    }

    /** Plain string map survives the SDK's existing durable metadata serializer without a custom object codec. */
    private Map<String,String> asMetadata() {
        InboxWait w = occurrence;
        Map<String,String> m = new LinkedHashMap<>();
        m.put("tenant",w.tenant()); m.put("instance",w.instanceId().value()); m.put("application",w.applicationId());
        m.put("engine",w.engineInstanceId()); m.put("namespace",w.namespace()); m.put("definition",w.definition());
        m.put("version",w.version()); m.put("position",w.taskPosition()); m.put("iteration",Integer.toString(w.iteration()));
        m.put("route",w.route()); m.put("type",w.eventType()); m.put("filter",w.filterDigest());
        m.put("maxWait",w.maxWait().toString()); m.put("receipt",receiptFingerprint); m.put("attempt",Integer.toString(attempt));
        return m;
    }

    static List<InboxCheckpointMarker> forCheckpoint(WorkflowContextData workflow, TaskContextData task) {
        List<InboxCheckpointMarker> markers = new ArrayList<>();
        for (var entry : workflow.instanceData().metadata().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) continue;
            if (!(entry.getValue() instanceof Map<?,?> m)) throw new IllegalStateException("malformed checkpoint marker");
            // Other parallel waits/earlier iterations may be in the workflow metadata snapshot. They are NOT this checkpoint.
            if (!Objects.equals(m.get("position"),task.position().jsonPointer())
                    || !Objects.equals(m.get("iteration"),Integer.toString(task.iteration()))) continue;
            InboxWait wait = new InboxWait(text(m,"tenant"),Ulid.parse(text(m,"instance")),text(m,"application"),
                    text(m,"engine"),text(m,"namespace"),text(m,"definition"),text(m,"version"),text(m,"position"),
                    Integer.parseInt(text(m,"iteration")),text(m,"route"),text(m,"type"),text(m,"filter"),
                    Duration.parse(text(m,"maxWait")));
            if (!entry.getKey().equals(PREFIX+wait.digest())) throw new IllegalStateException("checkpoint marker identity changed");
            var marker = new InboxCheckpointMarker(wait,text(m,"receipt"),Integer.parseInt(text(m,"attempt")));
            marker.requireCheckpoint(workflow,task); markers.add(marker);
        }
        return List.copyOf(markers);
    }
    private static String text(Map<?,?> values, String key) {
        if (!(values.get(key) instanceof String value)) throw new IllegalStateException("missing checkpoint marker field " + key);
        return value;
    }
}
