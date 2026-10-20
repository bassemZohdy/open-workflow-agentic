package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

/** Internal runtime seam for durable inbox acknowledgement; contains identity only, never credentials or payload. */
@FunctionalInterface
public interface CheckpointCommitObserver {
    record Checkpoint(String engineInstanceId, String namespace, String definition, String version, String taskPosition, int iteration) { }
    void committed(PersistenceCommitGate.Operation operation, Checkpoint checkpoint);
}
