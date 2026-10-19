package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.List;
import java.util.Optional;

/**
 * The instance registry (the {@code task.instance} table and its history). The implementation enforces the rules
 * of this port, so every write path is guarded, not only the service.
 *
 * <p><b>Failure semantics: fail closed.</b> A missing row is an empty optional, never a new row. A version mismatch
 * is {@link StaleVersionException}. A status change that {@link InstanceStateMachine} forbids is
 * {@link IllegalTransitionException}. A database failure is {@link StoreUnavailableException}. Every successful write
 * also writes one history row in the same transaction.
 */
public interface InstanceStore {

    /**
     * Inserts the row for a request, unless a row with the same tenant and idempotency key exists. The candidate must
     * be in status STARTING with version 0. The insert and its history row are one transaction.
     *
     * @return the row now stored for the key, and whether this call created it
     */
    Insertion insertIfAbsent(InstanceRecord candidate, String reason);

    /** The instance with this id in this tenant. Another tenant's id is empty, not forbidden, so ids do not leak. */
    Optional<InstanceRecord> find(String tenant, Ulid id);

    /**
     * Replaces a row if its stored version is {@code expectedVersion}. The new row's version must be
     * {@code expectedVersion + 1}. Identity and input must not change. A status change must be allowed by
     * {@link InstanceStateMachine}. The engine instance id may be set once and never changed.
     *
     * @return the stored row after the change
     */
    InstanceRecord replace(InstanceRecord next, long expectedVersion, String reason,
                           io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject actor);

    /** The history of one instance, oldest first. Append-only in the database. */
    List<HistoryEntry> history(Ulid id);

    /** The result of {@link #insertIfAbsent}. */
    record Insertion(InstanceRecord record, boolean created) {
    }
}
