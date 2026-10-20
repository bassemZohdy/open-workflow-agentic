package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

/** Original node is already held/possibly called. Historical data is not permission to retry. */
public final class ModelCallAlreadyReservedException extends IllegalStateException {
    public ModelCallAlreadyReservedException() { super("model node is already reserved; reconcile the original call"); }
}
