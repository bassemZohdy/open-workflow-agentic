package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;

/**
 * One history row of an instance. {@code fromStatus} is null for the insert. A change that keeps the status has
 * {@code fromStatus} equal to {@code toStatus}.
 *
 * @param version    the row version after the change; the history of one row runs 0, 1, 2 ... without gaps
 * @param fromStatus the status before the change, or null for the insert
 * @param toStatus   the status after the change
 * @param reason     a short machine-readable reason, for example {@code start.requested}
 * @param actor      who made the change
 * @param occurredAt when the change was written
 */
public record HistoryEntry(long version, InstanceStatus fromStatus, InstanceStatus toStatus, String reason,
                           Subject actor, Instant occurredAt) {
}
