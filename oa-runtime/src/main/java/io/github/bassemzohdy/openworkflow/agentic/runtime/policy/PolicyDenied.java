package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import java.util.Objects;

/**
 * Thrown by {@link Pep#guard} instead of running a call whose decision is not ALLOW. It carries the refusing decision,
 * so the caller can record it and return the refusal to the agent. The message does not contain the request.
 */
public final class PolicyDenied extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Decision decision;

    public PolicyDenied(Decision decision) {
        super("policy refused the call: " + Objects.requireNonNull(decision, "decision").outcome()
                + " by " + decision.decidedBy() + " (" + decision.policy().policyId()
                + " version " + decision.policy().policySetVersion() + ")");
        this.decision = decision;
    }

    /** The refusal, which is DENY or ERROR. */
    public Decision decision() {
        return decision;
    }
}
