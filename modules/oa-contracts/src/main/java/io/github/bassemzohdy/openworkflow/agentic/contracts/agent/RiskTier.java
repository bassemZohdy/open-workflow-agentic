package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

/**
 * Risk classification of an agent task (M02). The tier is set when the task is approved and decides which controls
 * apply. A narrowed task may keep or raise its tier, never lower it.
 */
public enum RiskTier {
    /** Reversible actions on non-sensitive data. */
    LOW,
    /** Actions with limited, recoverable impact, or access to personal data. */
    MEDIUM,
    /** Irreversible or financially significant actions; human approval applies. */
    HIGH;

    /** True when this tier is at least as strict as {@code other}. */
    public boolean atLeast(RiskTier other) {
        return compareTo(other) >= 0;
    }
}
