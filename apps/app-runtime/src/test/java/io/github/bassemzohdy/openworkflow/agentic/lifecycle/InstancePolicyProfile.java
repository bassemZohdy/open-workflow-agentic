package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowEngine;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.LifecycleFaults;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartResult;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.CedarPolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.ManifestPolicySnapshotSource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WP-103 instance API policy tests: trusted development headers (for the scope cases) next to verified tokens, the real
 * operations Cedar snapshot behind the enforcement point, and two controls that only this profile enables: a decision
 * path that can be made slow or unavailable, and a lifecycle service that blocks after its real work. The controls are
 * static switches, reset by every test.
 */
public class InstancePolicyProfile implements QuarkusTestProfile {

    /** Milliseconds the audit write of a policy decision blocks; it is the blocking part before any effect. */
    static volatile long auditDelayMillis;
    /** Every policy decision is audited, and the audit write fails. */
    static volatile boolean auditFails;
    /** The policy engine throws. */
    static volatile boolean policyFails;
    /** Milliseconds the lifecycle service blocks AFTER a real start / a real read. */
    static volatile long startBlockMillis;
    static volatile long readBlockMillis;

    static void reset() {
        auditDelayMillis = 0;
        auditFails = false;
        policyFails = false;
        startBlockMillis = 0;
        readBlockMillis = 0;
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("agentic.dev.trust-caller-headers", "true");
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(Controlled.class, BlockingService.class);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The operations Pep of production (real Cedar snapshot, real audit trail), with the failure switches in front. */
    @Alternative
    @ApplicationScoped
    public static class Controlled {
        @Produces
        @Singleton
        public Pep pep(AuditTrail trail) {
            CedarPolicyDecisionPoint cedar = new CedarPolicyDecisionPoint();
            cedar.refresh(ManifestPolicySnapshotSource.ofClasspath(Thread.currentThread().getContextClassLoader(),
                    "policy/operations"));
            PolicyDecisionPoint engine = (AuthorizationRequest request) -> {
                if (policyFails) {
                    throw new IllegalStateException("the policy engine is down");
                }
                return cedar.decide(request);
            };
            AuditSink sink = event -> {
                sleep(auditDelayMillis);
                if (auditFails) {
                    throw new IllegalStateException("the audit trail is down");
                }
                trail.record(event);
            };
            return new Pep(engine, sink);
        }
    }

    /** The real lifecycle service; after its real work it blocks, as a slow database or engine would. */
    @Alternative
    @Singleton
    public static class BlockingService extends InstanceService {
        @Inject
        public BlockingService(InstanceStore store, WorkflowEngine engine, LifecycleFaults faults) {
            super(store, engine, faults);
        }

        @Override
        public StartResult start(StartRequest request, InstanceStore.InsertHook onCreate) {
            StartResult result = super.start(request, onCreate);
            sleep(startBlockMillis);
            return result;
        }

        @Override
        public Optional<InstanceRecord> get(String tenant, Ulid id) {
            Optional<InstanceRecord> row = super.get(tenant, id);
            sleep(readBlockMillis);
            return row;
        }
    }
}
