package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.quarkiverse.flow.persistence.jpa.JpaInstanceReader;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.InboxCheckpointFinalizer;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Selects a guarded handler at the existing injection point. The original Quarkus customizer still applies its
 * workflow exclusions and all-strategy correlation factory; the original JPA reader is retained by default. An
 * explicitly enabled itest-only composition exercises the candidate reader in process-restart tests.
 */
@ApplicationScoped
public class CheckpointingHandlersProducer {
    @Produces @ApplicationScoped @Alternative @Priority(1)
    public PersistenceInstanceHandlers guardedHandlers(JpaInstanceWriter writer, JpaInstanceReader reader,
            JpaInstanceOperations operations, PendingCancellationReceiptWriter cancellationWriter,
            PersistenceCommitGate gate, Instance<CheckpointCommitObserver> observers, InboxCheckpointFinalizer finalizer,
            @ConfigProperty(name = "agentic.test.pending-cancellation-reader", defaultValue = "false") boolean testReader,
            @ConfigProperty(name = "quarkus.profile", defaultValue = "prod") String profile) {
        CheckpointCommitObserver observer = (op, checkpoint) -> observers.forEach(o -> o.committed(op, checkpoint));
        var normal = new CheckpointingWriter(writer, gate, observer, finalizer);
        if (!testReader) return new PersistenceInstanceHandlers(normal, reader);
        if (!"itest".equals(profile))
            throw new IllegalStateException("pending cancellation test reader requires the itest profile");
        var cancellation = new CheckpointingWriter(cancellationWriter, gate, observer, finalizer);
        return new PersistenceInstanceHandlers(normal, new CancellablePersistenceReader(operations, cancellation));
    }
}
