package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

/**
 * The only way agent code reaches a model. Before the provider {@link LlmClient} is called, the approved context of
 * the call is read and {@link Pep#modelCall} decides; the PEP records the decision through the audit sink before
 * returning, and an audit failure turns the decision into ERROR. The provider is called only for ALLOW.
 *
 * <p>Zero physical provider calls happen when: there is no approved context, or its remaining budget is zero ({@link ModelCallRefused}); the policy
 * says DENY or ERROR, or its engine or the audit record fails ({@link
 * io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied}); or the request to the PEP cannot be built.
 * Nothing here retries: one allowed decision admits exactly one call to {@link LlmClient#complete}. A provider that
 * retries internally must be configured to make one attempt, or its attempts are not governed (WP-103 handoff).
 *
 * <p>The {@code ArchitectureTest} rule "model calls only through GovernedLlmClient" keeps other classes from calling
 * {@link LlmClient#complete} directly.
 */
@ApplicationScoped
public class GovernedLlmClient {

    @Inject
    LlmClient provider;

    @Inject
    @ModelPolicy
    Pep pep;

    @Inject
    ApprovedModelContext context;

    public GovernedLlmClient() {
    }

    public GovernedLlmClient(LlmClient provider, Pep pep, ApprovedModelContext context) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.pep = Objects.requireNonNull(pep, "pep");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Completes through the provider only after an audited ALLOW; otherwise throws and the provider is not touched. */
    public String complete(String system, String user) {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        var approved = context.current()
                .orElseThrow(() -> new ModelCallRefused("no approved context for this model call"));
        if (approved.budgetRemainingMicros() == 0L) {
            // refused here, not by policy: a permissive policy may ignore the budget it is shown
            throw new ModelCallRefused("no model budget remaining for this model call");
        }
        var decision = pep.modelCall(approved.agent(), approved.task(), approved.modelId(),
                approved.budgetRemainingMicros());
        return Pep.guard(decision, () -> provider.complete(system, user));
    }
}
