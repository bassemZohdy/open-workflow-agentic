package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

/**
 * Thrown when a narrowed {@link AgentTaskContract} would grant more than its parent: a tool or data scope the parent
 * does not permit, a raised limit, or a lowered risk tier. A delegation can only narrow authority (ADR 0021), so
 * this is a refusal, not a warning.
 */
public final class PermissionWideningException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public PermissionWideningException(String message) {
        super(message);
    }
}
