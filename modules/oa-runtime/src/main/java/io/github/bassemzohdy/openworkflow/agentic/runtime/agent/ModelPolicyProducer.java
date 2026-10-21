package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.CedarPolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.ManifestPolicySnapshotSource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The model-call PEP. It loads the signed policy snapshot from {@code agentic.model.policy-folder}. No model policy
 * snapshot ships with the runtime, so until a deployment supplies one the engine is unavailable, every decision is
 * ERROR, and every model call is refused before the provider (fail closed, not a default allow).
 */
@ApplicationScoped
public class ModelPolicyProducer {

    private static final Logger LOG = Logger.getLogger(ModelPolicyProducer.class);

    @ConfigProperty(name = "agentic.model.policy-folder", defaultValue = "policy/model")
    String policyFolder;

    @Produces
    @Singleton
    @ModelPolicy
    public Pep modelPep(AuditTrail audit) {
        return build(audit, policyFolder);
    }

    /** The PEP over the snapshot in {@code folder}; an absent or invalid snapshot leaves the engine unavailable. */
    static Pep build(AuditSink audit, String folder) {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        var outcome = engine.refresh(
                ManifestPolicySnapshotSource.ofClasspath(Thread.currentThread().getContextClassLoader(), folder));
        if (!(outcome instanceof CedarPolicyDecisionPoint.RefreshOutcome.Accepted)) {
            LOG.warnf("no model policy snapshot loaded from %s (%s): every model call is refused", folder, outcome);
        }
        return new Pep(engine, audit);
    }
}
