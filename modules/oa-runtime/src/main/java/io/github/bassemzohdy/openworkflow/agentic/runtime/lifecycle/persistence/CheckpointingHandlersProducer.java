package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.quarkiverse.flow.persistence.jpa.JpaInstanceReader;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.InboxCheckpointFinalizer;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

/**
 * Selects a guarded handler at the existing injection point. The original Quarkus customizer still applies its
 * workflow exclusions and all-strategy correlation factory; the original JPA reader is retained for restore.
 */
@ApplicationScoped
public class CheckpointingHandlersProducer {
    @Produces @ApplicationScoped @Alternative @Priority(1)
    public PersistenceInstanceHandlers guardedHandlers(JpaInstanceWriter writer, JpaInstanceReader reader,
            PersistenceCommitGate gate, Instance<CheckpointCommitObserver> observers, InboxCheckpointFinalizer finalizer) {
        CheckpointCommitObserver observer = (op, checkpoint) -> observers.forEach(o -> o.committed(op, checkpoint));
        return new PersistenceInstanceHandlers(new CheckpointingWriter(writer, gate, observer,finalizer), reader);
    }
}
