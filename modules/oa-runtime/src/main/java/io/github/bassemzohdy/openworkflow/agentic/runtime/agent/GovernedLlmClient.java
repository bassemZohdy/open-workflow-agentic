package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
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

    /**
     * Controlled variant: the same boundary as {@link #complete(String, String)}, additionally bound to the SAME
     * private {@link JdbcResourceExecutionGate.Running} handle minted for a fresh, acknowledged gate callback. The
     * handle is rechecked against {@code expected} (its exact entry, live fence and invalidation) at four points:
     * before the approved-context lookup, after the lookup and before the policy question, after an audited ALLOW
     * immediately before the physical provider, and after the provider returns before its output is released.
     * A missing, closed, mismatched or interrupted control throws and the provider is not reached, or its output is
     * withheld.
     *
     * <p>This is not an approved-context source, reservation, or task mapping: the context still comes from the
     * injected {@link ApprovedModelContext} (empty by default, which refuses) and the zero-budget refusal is
     * unchanged. The caller supplies the handle; this client does not store it, read it from a thread, or accept a
     * fence callback. Everything here is synchronous on the calling thread and starts no workers.
     */
    public String complete(JdbcResourceExecutionGate.Running control, ResourceExecutionEntry expected,
            String system, String user) {
        if (control == null) {
            throw new ModelCallRefused("no running execution control for this model call");
        }
        if (expected == null) {
            throw new ModelCallRefused("no expected execution entry for this model call");
        }
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        control.requireCurrent(expected);
        var approved = context.current()
                .orElse(null); // lookup may block; the control is rechecked before its answer is acted on
        control.requireCurrent(expected);
        if (approved == null) {
            throw new ModelCallRefused("no approved context for this model call");
        }
        if (approved.budgetRemainingMicros() == 0L) {
            throw new ModelCallRefused("no model budget remaining for this model call");
        }
        var decision = pep.modelCall(approved.agent(), approved.task(), approved.modelId(),
                approved.budgetRemainingMicros()); // may block in policy and audit; recheck on ALLOW below
        return Pep.guard(decision, () -> {
            control.requireCurrent(expected);
            String output = provider.complete(system, user);
            control.requireCurrent(expected); // private output is withheld if permission was lost meanwhile
            return output;
        });
    }
}
