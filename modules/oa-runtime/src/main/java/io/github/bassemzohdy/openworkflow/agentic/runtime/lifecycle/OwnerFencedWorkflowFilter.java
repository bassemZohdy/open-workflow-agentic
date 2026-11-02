package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.impl.WorkflowFilter;
import java.util.Objects;

/** Explicit wrapper for a selected SDK workflow-level filter; the caller must ensure its provider is selected. */
public final class OwnerFencedWorkflowFilter {
    private OwnerFencedWorkflowFilter() { }
    public static WorkflowFilter wrap(WorkflowFilter delegate, OwnerFencedTaskExecutorFactory.Check check) {
        Objects.requireNonNull(delegate); Objects.requireNonNull(check);
        return (workflow, task, input) -> {
            if (task == null) {
                try { check.requireCurrent(workflow); }
                catch (RuntimeException refused) { throw OwnerFencedTaskExecutorFactory.ownerLost(refused); }
            }
            return delegate.apply(workflow, task, input);
        };
    }
}
