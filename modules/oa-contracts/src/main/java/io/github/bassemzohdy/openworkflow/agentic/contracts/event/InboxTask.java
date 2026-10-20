package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Objects;

/** One SDK task occurrence, shared by ALL of its event registrations, independent of transport/owner/retry. */
public record InboxTask(String tenant, Ulid instanceId, String applicationId, String engineInstanceId,
                        String namespace, String definition, String version, String taskPosition, int iteration) {
    public InboxTask {
        tenant=Checks.requireText("tenant",tenant,256); Objects.requireNonNull(instanceId);
        applicationId=Checks.requireText("applicationId",applicationId,256);
        engineInstanceId=Checks.requireText("engineInstanceId",engineInstanceId,256);
        namespace=Checks.requireText("namespace",namespace,256); definition=Checks.requireText("definition",definition,256);
        version=Checks.requireText("version",version,128); taskPosition=Checks.requireText("taskPosition",taskPosition,1024);
        if(iteration<1) throw new IllegalArgumentException("task iteration must be positive");
    }
    public String digest() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.task.v1").text(tenant).text(instanceId.value())
                .text(applicationId).text(engineInstanceId).text(namespace).text(definition).text(version)
                .text(taskPosition).number(iteration).toBytes());
    }
}
