package io.github.bassemzohdy.openworkflow.agentic.runtime.operations;

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
 * Wiring of the policy enforcement point for the operations API (WP-105d).
 *
 * <p>The decision point is an embedded Cedar engine loaded from a classpath folder with a manifest
 * ({@code agentic.operations.policy-folder}, by default {@code policy/operations}). It is not exposed as a bean, so it
 * does not change what counts as a bound decision point for the audit API (docs/audit.md, section 7). When the folder
 * cannot be loaded, the engine has no snapshot and every decision is ERROR. Operators can then still cancel or pause
 * through the safe-recovery rule (docs/policy.md, section 6), and every other operation is refused.
 */
@ApplicationScoped
public class OperationsProducers {

    private static final Logger LOG = Logger.getLogger(OperationsProducers.class);

    @ConfigProperty(name = "agentic.operations.policy-folder", defaultValue = "policy/operations")
    String policyFolder;

    @Produces
    @Singleton
    public Pep operationsPep(AuditTrail audit) {
        CedarPolicyDecisionPoint engine = new CedarPolicyDecisionPoint();
        CedarPolicyDecisionPoint.RefreshOutcome outcome = engine.refresh(
                ManifestPolicySnapshotSource.ofClasspath(Thread.currentThread().getContextClassLoader(), policyFolder));
        if (outcome instanceof CedarPolicyDecisionPoint.RefreshOutcome.Accepted accepted) {
            LOG.infof("operations policy snapshot %s accepted from %s", accepted.versionId(), policyFolder);
        } else {
            LOG.errorf("operations policy snapshot not loaded from %s: %s; every operation is refused except the "
                    + "safe-recovery cancel and pause", policyFolder, outcome);
        }
        return new Pep(engine, audit);
    }
}
