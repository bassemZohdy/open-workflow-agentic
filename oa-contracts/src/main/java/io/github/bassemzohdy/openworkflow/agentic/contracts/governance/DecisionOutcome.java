package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

/**
 * Result of a policy decision. Only {@link #ALLOW} lets the action proceed. {@link #DENY} is a normal refusal.
 * {@link #ERROR} means the decision could not be made (no policy, engine failure, timeout); it is a refusal too.
 */
public enum DecisionOutcome {
    ALLOW,
    DENY,
    ERROR;

    /** True only for {@link #ALLOW}. Callers must test this, never {@code != DENY}. */
    public boolean permits() {
        return this == ALLOW;
    }
}
