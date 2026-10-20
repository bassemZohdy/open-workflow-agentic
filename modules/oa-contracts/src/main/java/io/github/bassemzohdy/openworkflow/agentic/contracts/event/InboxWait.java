package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable identity of a logical wait. Transport attempts, process ids and resume epochs are not identities. */
public record InboxWait(String tenant, Ulid instanceId, String applicationId, String engineInstanceId,
                        String namespace, String definition, String version, String taskPosition, int iteration,
                        String route, String eventType, String filterDigest, Duration maxWait) {
    private static final Pattern ROUTE = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    public InboxWait {
        tenant = Checks.requireText("tenant", tenant, 256); Objects.requireNonNull(instanceId);
        applicationId = Checks.requireText("applicationId", applicationId, 256);
        engineInstanceId = Checks.requireText("engineInstanceId", engineInstanceId, 256);
        namespace = Checks.requireText("namespace", namespace, 256);
        definition = Checks.requireText("definition", definition, 256);
        version = Checks.requireText("version", version, 128);
        taskPosition = Checks.requireText("taskPosition", taskPosition, 1024);
        // Preserve the SDK's position verbatim. Its default QueueWorkflowPosition emits "do/0/task";
        // StringBufferWorkflowPosition emits "/do/0/task". Normalizing would alias distinct persistence keys.
        if (iteration < 1) throw new IllegalArgumentException("wait iteration must be positive");
        route = Checks.requireMatching("route", route, ROUTE);
        eventType = Checks.requireText("eventType", eventType, 256);
        filterDigest = Checks.requireMatching("filterDigest", filterDigest, DIGEST);
        Objects.requireNonNull(maxWait);
        if (maxWait.compareTo(Duration.ofMillis(1)) < 0 || maxWait.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("wait duration must be from 1ms through 30 days");
        }
    }

    public String digest() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.wait.v1")
                .text(tenant).text(instanceId.value()).text(applicationId).text(engineInstanceId)
                .text(namespace).text(definition).text(version).text(taskPosition).number(iteration)
                .text(route).text(eventType).text(filterDigest).text(maxWait.toString()).toBytes());
    }

    /** Internal logical occurrence key. Producers use the deployment-approved {@link #route()}, not SDK identity. */
    public String key() { return route + "~" + digest(); }

    /** all/any registrations of this occurrence share ownership even when their filters/routes differ. */
    public InboxTask task() {
        return new InboxTask(tenant,instanceId,applicationId,engineInstanceId,namespace,definition,version,taskPosition,iteration);
    }
}
