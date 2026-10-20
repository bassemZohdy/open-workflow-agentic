package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PersistenceCommitGate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** A failed/unknown required checkpoint needs restore before this process accepts new work. */
@Readiness
@ApplicationScoped
public class CheckpointReadiness implements HealthCheck {
    @Inject PersistenceCommitGate gate;

    public HealthCheckResponse call() {
        var status = gate.status();
        var response = HealthCheckResponse.named("workflow-checkpoint-commits").status(status.healthy())
                .withData("inFlight", status.inFlight()).withData("heldCalls", status.heldCalls());
        status.failure().ifPresent(f -> response.withData("operation", f.operation().name())
                .withData("errorClass", f.errorClass()).withData("observedAt", f.observedAt().toString()));
        return response.build();
    }
}
