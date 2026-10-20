package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceReader;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CheckpointBindingTest {
    @Inject PersistenceInstanceHandlers handlers;
    @Inject PersistenceCommitGate gate;
    @Inject CheckpointReadiness readiness;

    @Test
    void realApplicationUsesGuardedWriterAndOriginalJpaRestoreReader() {
        assertInstanceOf(CheckpointingWriter.class, handlers.writer());
        assertInstanceOf(JpaInstanceReader.class, handlers.reader());
        assertTrue(gate.status().healthy());
        assertEquals(HealthCheckResponse.Status.UP, readiness.call().getStatus());
    }
}
