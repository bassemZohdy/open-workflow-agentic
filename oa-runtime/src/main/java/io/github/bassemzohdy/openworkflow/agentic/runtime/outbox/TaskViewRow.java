package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import java.time.Instant;

/**
 * One row of {@code task.task_view}: a workflow instance as the projection shows it (WP-105b).
 *
 * @param instanceId         the workflow instance id
 * @param tenant             the tenant that owns the instance
 * @param ownerKind          the kind of the subject that started the instance
 * @param ownerId            the subject that started the instance
 * @param definitionId       the pinned definition, {@code namespace/name}
 * @param definitionVersion  the pinned definition version
 * @param instanceStatus     the instance status at the last applied event
 * @param instanceVersion    the registry version of the instance at the last applied instance event
 * @param agentTaskId        the agent task of the last applied agent event, or null when there is none
 * @param agentTaskStatus    that agent task's status, or null
 * @param agentOutboxId      the outbox id of that agent event, or null
 * @param waitingForHuman    true when the instance or its current agent task waits for a person
 * @param lastReason         the reason of the last applied event
 * @param lastTransitionAt   the time of the last applied event
 * @param lastOutboxId       the outbox id of the last applied event
 * @param updatedAt          the time of the last applied event (the projector does not use its own clock)
 */
public record TaskViewRow(
        String instanceId,
        String tenant,
        String ownerKind,
        String ownerId,
        String definitionId,
        String definitionVersion,
        String instanceStatus,
        long instanceVersion,
        String agentTaskId,
        String agentTaskStatus,
        Long agentOutboxId,
        boolean waitingForHuman,
        String lastReason,
        Instant lastTransitionAt,
        long lastOutboxId,
        Instant updatedAt) {
}
