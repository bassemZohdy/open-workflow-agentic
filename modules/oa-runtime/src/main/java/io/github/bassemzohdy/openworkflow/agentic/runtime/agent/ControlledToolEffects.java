package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ApprovedToolProfile;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceToolCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.DurableEffects;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.Running;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Explicit synchronous composition of a canonical tool occurrence with the durable effect ledger and a live run.
 * The registered target must bind the validated request and enforce its own finite transport deadlines and identity.
 * This is not a tool registry, schema validator, occurrence producer, production approval source or gateway wiring.
 * The supplied occurrence, allocation, profile, budget and target must come from trusted composition, not a request.
 * The call is synchronous: it never returns while a target invocation it started is still running.
 */
public final class ControlledToolEffects {
    private final DurableEffects effects;
    private final Pep policy;
    private final Supplier<Optional<GovernedToolGateway.Approval>> approvals;

    public ControlledToolEffects(DurableEffects effects, Pep policy,
                                 Supplier<Optional<GovernedToolGateway.Approval>> approvals) {
        this.effects = Objects.requireNonNull(effects); this.policy = Objects.requireNonNull(policy);
        this.approvals = Objects.requireNonNull(approvals);
    }

    /** Returns a retained outcome, including unresolved INTENDED; callers must not interpret it as tool output. */
    public Effect execute(Running control, ResourceExecutionEntry entry, AllocatedResourceTask allocation,
                          ApprovedToolProfile profile, ResourceToolCall occurrence, ToolCall call,
                          GovernedToolGateway.ToolBudget budget, String registeredTarget, EffectTarget target) {
        Objects.requireNonNull(control); Objects.requireNonNull(entry); Objects.requireNonNull(allocation);
        Objects.requireNonNull(profile); Objects.requireNonNull(occurrence); Objects.requireNonNull(call);
        Objects.requireNonNull(budget); Objects.requireNonNull(registeredTarget); Objects.requireNonNull(target);
        control.requireCurrent(entry, occurrence.executor());
        var expected = ResourceToolCall.of(allocation, profile, entry, occurrence.executor(), occurrence.nodeOccurrence(), call);
        if (!expected.equals(occurrence)) throw new ModelCallRefused("tool occurrence does not match the request");
        var approved = current();
        if (approved == null || !approved.agent().equals(profile.agent()) || !approved.task().equals(allocation.allocation().contract())
                || approved.budgetRemainingMicros() == 0) throw new ModelCallRefused("no matching tool approval");
        Runnable current = () -> {
            control.requireCurrent(entry, occurrence.executor());
            if (!approved.equals(current())) throw new ModelCallRefused("tool approval changed");
            budget.requireCurrent(approved); budget.requireProfile(approved, profile);
            if (!approved.equals(current())) throw new ModelCallRefused("tool approval changed");
            control.requireCurrent(entry, occurrence.executor());
        };
        Runnable authorize = () -> {
            current.run();
            var decision = policy.toolCall(approved.agent(), approved.task(), occurrence.toolName(), approved.budgetRemainingMicros());
            Pep.guard(decision, () -> null);
            current.run();
        };
        authorize.run();
        var command = occurrence.effectCommand(registeredTarget);
        var fenced = new EffectTarget() {
            @Override public String target() { return registeredTarget; }
            private void targetMatches() {
                if (!registeredTarget.equals(target.target())) throw new IllegalStateException("registered tool target differs");
            }
            @Override public Duration replayWindow() {
                current.run(); targetMatches(); Duration window = target.replayWindow(); current.run(); return window;
            }
            @Override public Reply execute(String key) throws Exception {
                authorize.run(); targetMatches(); budget.admit(approved); current.run();
                return target.execute(key); // retain a known target outcome even if authority is lost during I/O
            }
            @Override public Reply lookup(String key) throws Exception {
                authorize.run(); targetMatches(); current.run(); return target.lookup(key);
            }
        };
        Effect result = effects.execute(command, fenced);
        current.run();
        return result;
    }

    private GovernedToolGateway.Approval current() {
        try { return approvals.get().orElse(null); }
        catch (RuntimeException unavailable) { return null; }
    }
}
