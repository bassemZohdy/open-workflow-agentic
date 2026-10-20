package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

/** No fresh reservation/callback: the original durable call/count/cost allowance is exhausted. */
public final class ModelBudgetExhaustedException extends IllegalStateException {
    public ModelBudgetExhaustedException(Throwable cause) { super("model reservation allowance is exhausted", cause); }
}
