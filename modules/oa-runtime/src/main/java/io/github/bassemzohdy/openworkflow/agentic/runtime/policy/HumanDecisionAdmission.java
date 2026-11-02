package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanActionFacts;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/**
 * Preflight for a human decision using verified caller authority and retained task facts. It performs no mutation or
 * wakeup and is not a durable authorization: the consumer must re-read and lock the same task version and wait,
 * recheck authority and expiry immediately before committing, and meet the audit and delivery requirements.
 * This component is not wired to an endpoint or a decision store.
 */
public final class HumanDecisionAdmission {
    private final Pep pep;
    private final Clock clock;

    public HumanDecisionAdmission(Pep pep, Clock clock) {
        this.pep = Objects.requireNonNull(pep, "pep");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Decision check(CallerAuthority authority, HumanDecision request, HumanActionFacts facts) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(facts, "facts");
        var caller = authority.caller();
        if (authority.delegated() || !request.actor().equals(caller.subject())
                || !request.tenant().equals(caller.tenant()) || !request.tenant().equals(facts.tenant())
                || !request.taskId().equals(facts.taskId()) || request.expectedVersion() != facts.taskVersion()) {
            throw new SecurityException("human decision does not match current caller and task facts");
        }
        var required = Set.of(HumanAction.DECIDE.requiredScope());
        authority.requireCurrent(required);
        Decision result = pep.humanTaskAction(caller.subject(), caller.tenant(), caller.scopes(), facts, HumanAction.DECIDE);
        if (result.allows()) {
            authority.requireCurrent(required);
            if (facts.dueAt() != null && !clock.instant().isBefore(facts.dueAt())) {
                throw new SecurityException("human task expired during authorization");
            }
        }
        return result;
    }
}
