package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.CedarPolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.ManifestPolicySnapshotSource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;

/** Separate binding: ingress does not replace the operations policy or bind a global PDP for the audit API. */
@ApplicationScoped
public class EventPolicyProducer {
    void validateBindings(@Observes StartupEvent event, EventIngressSettings settings) {
        EventIngress.validateSettings(settings);
    }

    @Produces @Singleton @EventPolicy
    public Pep ingressPep(AuditTrail audit, EventIngressSettings settings) {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        engine.refresh(ManifestPolicySnapshotSource.ofClasspath(Thread.currentThread().getContextClassLoader(), settings.policyFolder()));
        // A missing/corrupt snapshot leaves this engine unavailable. DATA_ACCESS never uses safe recovery.
        return new Pep(engine, audit);
    }
}
