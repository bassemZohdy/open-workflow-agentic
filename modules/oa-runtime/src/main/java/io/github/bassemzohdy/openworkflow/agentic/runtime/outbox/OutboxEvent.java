package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * An outbox row as a consumer sees it.
 *
 * @param id               the outbox id: monotonic, and in commit order within a partition
 * @param aggregateType    the kind of changed row
 * @param aggregateId      the changed row's id
 * @param aggregateVersion the row version after the change
 * @param partitionKey     the workflow instance id
 * @param eventType        for example {@code agent_task.status_changed}
 * @param payload          the JSON payload written with the change
 * @param occurredAt       the time of the change
 * @param attempts         failed deliveries of this row before this one
 */
public record OutboxEvent(
        long id,
        OutboxEntry.AggregateType aggregateType,
        String aggregateId,
        long aggregateVersion,
        String partitionKey,
        String eventType,
        JsonNode payload,
        Instant occurredAt,
        int attempts) {
}
