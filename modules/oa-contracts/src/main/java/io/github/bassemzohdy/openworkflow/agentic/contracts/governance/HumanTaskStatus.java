package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

/** Lifecycle state of a {@link HumanTask}. */
public enum HumanTaskStatus {
    /** Waiting for someone to take it. */
    OPEN,
    /** Taken by an assignee and not yet finished. */
    IN_PROGRESS,
    /** Finished by the assignee. */
    COMPLETED,
    /** Withdrawn because the work is no longer needed. */
    CANCELLED,
    /** Passed its due time without completion. */
    EXPIRED;

    /** True for statuses that need an assignee. */
    public boolean requiresAssignee() {
        return this == IN_PROGRESS || this == COMPLETED;
    }
}
