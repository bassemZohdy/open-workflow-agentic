package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceModelCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ApprovedModelProfile;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceModelCalls;
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

    // Explicit trusted composition only; the CDI/default path does not activate reservations.
    private ResourceModelCalls reservations;

    public GovernedLlmClient() {
    }

    public GovernedLlmClient(LlmClient provider, Pep pep, ApprovedModelContext context) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.pep = Objects.requireNonNull(pep, "pep");
        this.context = Objects.requireNonNull(context, "context");
    }

    public GovernedLlmClient(LlmClient provider, Pep pep, ApprovedModelContext context, ResourceModelCalls reservations) {
        this(provider, pep, context);
        this.reservations = Objects.requireNonNull(reservations, "reservations");
    }

    /** Completes only after an audited ALLOW and matching approval; lost approval withholds a returned answer. */
    public String complete(String system, String user) {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        var approved = context.current()
                .orElseThrow(() -> new ModelCallRefused("no approved context for this model call"));
        // Use the same pinned-approval boundary as the gateway. The graph calls this overload directly.
        return complete(approved, system, user);
    }

    /**
     * Gateway path bound to the approval the gateway matched to its request. A changing context cannot let a call
     * checked for one task or model borrow another approval. Context lookup and policy/audit can block, so the same
     * approval is required again immediately before and after the provider call. This is not a durable reservation or a
     * replacement for the execution-entry authority fence.
     */
    String complete(ApprovedModelContext.Approved expected, String system, String user) {
        return complete(expected, system, user, null);
    }

    String complete(ApprovedModelContext.Approved expected, String system, String user, Integer maxOutputTokens) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        requireSameApproval(expected);
        if (expected.budgetRemainingMicros() == 0L) {
            throw new ModelCallRefused("no model budget remaining for this model call");
        }
        if (maxOutputTokens != null && !provider.supportsOutputTokenLimit()) {
            throw new ModelCallRefused("provider cannot enforce an output-token limit");
        }
        var decision = pep.modelCall(expected.agent(), expected.task(), expected.modelId(), expected.budgetRemainingMicros());
        return Pep.guard(decision, () -> {
            requireProviderModel(expected.modelId());
            requireSameApproval(expected);
            final String output;
            try {
                output = maxOutputTokens == null ? provider.complete(system, user)
                        : provider.complete(system, user, maxOutputTokens);
            } catch (RuntimeException providerFailure) {
                // Provider exceptions must never masquerade as a refusal before the provider was reached.
                throw new ProviderCallFailed();
            }
            try {
                requireSameApproval(expected);
            } catch (ModelCallRefused lost) {
                throw new ApprovalLostAfterCall();
            }
            return output;
        });
    }

    /** The provider was reached, so callers must not report a pre-call rejection. */
    static final class ApprovalLostAfterCall extends RuntimeException { }
    /** Sanitized failure after entering the provider, irrespective of the provider's exception type. */
    static final class ProviderCallFailed extends RuntimeException { }

    private void requireProviderModel(String approvedModel) {
        final String actual;
        try { actual = provider.configuredModelId(); }
        catch (RuntimeException unavailable) { throw new ModelCallRefused("provider model identity is unavailable"); }
        if (!approvedModel.equals(actual)) throw new ModelCallRefused("provider model differs from approval");
    }

    private void requireSameApproval(ApprovedModelContext.Approved expected) {
        final boolean matches;
        try {
            matches = context.current().filter(expected::equals).isPresent();
        } catch (RuntimeException unavailable) {
            throw new ModelCallRefused("the approved model context is unavailable");
        }
        if (!matches) {
            throw new ModelCallRefused("the approved model context changed during this call");
        }
    }

    /**
     * Controlled variant: the same boundary as {@link #complete(String, String)}, additionally bound to the SAME
     * private {@link JdbcResourceExecutionGate.Running} handle minted for a fresh, acknowledged gate callback. The
     * handle is rechecked against {@code expected} (its exact entry, live fence and invalidation) at four points:
     * before and after each potentially blocking approval lookup, after an audited ALLOW, immediately before the
     * physical provider, and after the provider returns before its output is released. The approved context is pinned
     * across policy and provider work, just as in the ordinary path.
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
            requireProviderModel(approved.modelId());
            control.requireCurrent(expected);
            requireSameApproval(approved);
            control.requireCurrent(expected); // approval lookup can itself block
            final String output;
            try {
                output = provider.complete(system, user);
            } catch (RuntimeException providerFailure) {
                throw new ProviderCallFailed();
            }
            control.requireCurrent(expected);
            try {
                requireSameApproval(approved);
            } catch (ModelCallRefused lost) {
                throw new ApprovalLostAfterCall();
            }
            control.requireCurrent(expected); // the final approval lookup can itself block
            return output;
        });
    }
    /**
     * Opt-in synchronous model consumer: a fresh durable reservation encloses the gateway's audited policy and one
     * trusted deadline-enforcing transport attempt. The allocation, real node coordinates and private running handle
     * come from trusted execution, not request fields. Reservation ceilings are held even after refusal or uncertainty;
     * the returned string is not measured usage and is never evidence of a refund or approval for a retry.
     */
    public String completeReserved(JdbcResourceExecutionGate.Running control, AllocatedResourceTask allocation,
                                   ResourceModelCall occurrence, ModelCall call) {
        Objects.requireNonNull(control); Objects.requireNonNull(allocation); Objects.requireNonNull(occurrence);
        Objects.requireNonNull(call);
        if (reservations == null || !(provider instanceof ReservedLlmClient transport))
            throw new ModelCallRefused("reserved model transport is unavailable");
        var entry = occurrence.entry();
        control.requireCurrent(entry, occurrence.executor());
        var expected = ResourceModelCall.of(allocation, entry, occurrence.executor(), occurrence.nodeOccurrence(), call);
        if (!expected.equals(occurrence)) throw new ModelCallRefused("model occurrence does not match the request");
        var approved = context.current().orElse(null);
        control.requireCurrent(entry, occurrence.executor());
        var profile = allocation.allocation().modelProfile();
        if (approved == null || !approved.agent().equals(profile.agent())
                || !approved.task().equals(allocation.allocation().contract())
                || !approved.modelId().equals(profile.modelId()) || approved.budgetRemainingMicros() == 0)
            throw new ModelCallRefused("no matching model approval");
        requireReservedTransport(transport, profile, call);
        control.requireCurrent(entry, occurrence.executor());
        requireSameApproval(approved);
        control.requireCurrent(entry, occurrence.executor());
        String result = reservations.invokeOnce(occurrence,
                candidate -> control.requireCurrent(candidate, occurrence.executor()), reservation -> {
                    if (!reservation.call().equals(occurrence) || !reservation.task().equals(allocation))
                        throw new ModelCallRefused("model reservation differs from allocation");
                    control.requireCurrent(entry, occurrence.executor());
                    requireSameApproval(approved);
                    control.requireCurrent(entry, occurrence.executor());
                    requireReservedTransport(transport, profile, call);
                    control.requireCurrent(entry, occurrence.executor());
                    var decision = pep.modelCall(approved.agent(), approved.task(), approved.modelId(),
                            reservation.protectedCallBudgetMicros());
                    return Pep.guard(decision, () -> {
                        control.requireCurrent(entry, occurrence.executor());
                        requireSameApproval(approved);
                        control.requireCurrent(entry, occurrence.executor());
                        return () -> {
                            control.requireCurrent(entry, occurrence.executor());
                            requireSameApproval(approved);
                            control.requireCurrent(entry, occurrence.executor());
                            requireReservedTransport(transport, profile, call);
                            control.requireCurrent(entry, occurrence.executor());
                            requireSameApproval(approved);
                            control.requireCurrent(entry, occurrence.executor());
                            try { return transport.completeReserved(call.prompt(), call.maxOutputTokens(), reservation.callDeadline()); }
                            catch (RuntimeException providerFailure) { throw new ProviderCallFailed(); }
                        };
                    });
                });
        control.requireCurrent(entry, occurrence.executor());
        try { requireSameApproval(approved); }
        catch (ModelCallRefused lost) { throw new ApprovalLostAfterCall(); }
        control.requireCurrent(entry, occurrence.executor());
        return result;
    }

    private void requireReservedTransport(ReservedLlmClient transport, ApprovedModelProfile profile, ModelCall call) {
        requireProviderModel(profile.modelId());
        final String actual;
        final long ceiling;
        try {
            actual = transport.reservedConfigurationFingerprint();
            ceiling = transport.maximumCostMicros(call.prompt(), call.maxOutputTokens());
        } catch (RuntimeException unavailable) {
            throw new ModelCallRefused("reserved transport configuration unavailable");
        }
        if (!profile.configurationFingerprint().equals(actual))
            throw new ModelCallRefused("reserved transport configuration differs from approval");
        if (ceiling < 0 || ceiling > profile.perCallCeilingMicros())
            throw new ModelCallRefused("reserved transport price exceeds approval");
    }

}
