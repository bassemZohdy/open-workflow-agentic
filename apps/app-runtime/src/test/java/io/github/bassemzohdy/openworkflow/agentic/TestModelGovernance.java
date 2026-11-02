package io.github.bassemzohdy.openworkflow.agentic;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ApprovedModelContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ModelPolicy;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.JdbcNodeBudget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only seam for boot tests that are not about the model boundary: an approved context and a model PEP that
 * allows, with every decision recorded in the real audit trail. Neither exists in production, where the defaults
 * approve nothing. A test of the refusal flips {@link #APPROVED} off and restores it.
 */
public final class TestModelGovernance {

    /** When false the context has nothing approved, as in production. */
    public static final AtomicBoolean APPROVED = new AtomicBoolean(true);
    private static final ConcurrentHashMap<String, ApprovedModelContext.Approved> TASKS = new ConcurrentHashMap<>();
    private static final AtomicReference<ApprovedModelContext.Approved> CURRENT = new AtomicReference<>();

    /** Register the approved test task for the same canonical thread that the request will use. */
    public static void prepare(JdbcNodeBudget budgets, String thread) {
        CURRENT.set(TASKS.computeIfAbsent(thread, ignored -> {
            var task = new AgentTaskContract(UlidGenerator.shared().next(), "boot test", Set.of(),
                    Set.of("cases:read"), RiskTier.LOW, new ExecutionLimits(20, Duration.ofMinutes(10), 1_000_000L),
                    new Subject(SubjectKind.USER, "boot-test"), Ulid.parse("01CP2ZZKBKACTAV9WEVGEMMVRZ"));
            var agent = new Subject(SubjectKind.AGENT, "research-agent");
            budgets.registerNew(thread, agent, task);
            return new ApprovedModelContext.Approved(agent, task, "stub", 1_000_000L);
        }));
    }

    private TestModelGovernance() {
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class Context implements ApprovedModelContext {
        @Override
        public Optional<Approved> current() {
            if (!APPROVED.get()) {
                return Optional.empty();
            }
            return Optional.ofNullable(CURRENT.get());
        }
    }

    @ApplicationScoped
    public static class Producers {
        @Produces
        @Singleton
        @ModelPolicy
        @Alternative
        @Priority(1)
        public Pep allowingModelPep(AuditTrail audit) {
            return new Pep(request -> new Decision("boot-test-engine", DecisionOutcome.ALLOW, "boot test",
                    new PolicyRef("boot-test", "1"), Instant.now()), audit);
        }
    }
}
