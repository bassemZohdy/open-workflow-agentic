package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

/** Thrown when a run budget is spent: the next iteration, the elapsed time or the cost would pass its limit. */
public final class BudgetExhausted extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final RunBudget.Limit limit;

    public BudgetExhausted(RunBudget.Limit limit, String message) {
        super(message);
        this.limit = limit;
    }

    /** Which limit was reached. */
    public RunBudget.Limit limit() {
        return limit;
    }
}
