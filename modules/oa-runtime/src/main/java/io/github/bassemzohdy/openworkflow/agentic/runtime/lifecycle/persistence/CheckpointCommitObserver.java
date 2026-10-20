package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

/**
 * Internal runtime seam for durable inbox acknowledgement; contains identity only, never credentials or payload.
 * A successful writer stage has only the durability promised by that operation/backend. In particular,
 * the pinned JPA writer does not persist on TASK_STARTED; that notification cannot reserve a durable
 * business occurrence. Consumers must bind acknowledgement to an operation with an actual commit.
 */
@FunctionalInterface
public interface CheckpointCommitObserver {
    record Checkpoint(String engineInstanceId, String namespace, String definition, String version, String taskPosition, int iteration) { }
    void committed(PersistenceCommitGate.Operation operation, Checkpoint checkpoint);
}
