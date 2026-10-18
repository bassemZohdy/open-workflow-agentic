package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.Objects;

/**
 * Work that a person must do before an agent or workflow continues (M05, M32): approve, redirect or take over.
 *
 * <p>Invariants: an {@code IN_PROGRESS} or {@code COMPLETED} task has an assignee; a due time, when given, is after
 * creation.
 *
 * @param id           the task id
 * @param agentTaskId  the agent task that is waiting, or null when a workflow raised the task
 * @param title        short summary, at most {@link #MAX_TITLE_LENGTH} characters
 * @param instructions what the person must decide or do, at most {@link #MAX_INSTRUCTIONS_LENGTH} characters
 * @param status       the lifecycle state
 * @param assignee     the person who took the task, or null while it is open
 * @param createdAt    when the task was created
 * @param dueAt        when the task expires, or null for none
 */
public record HumanTask(
        Ulid id,
        Ulid agentTaskId,
        String title,
        String instructions,
        HumanTaskStatus status,
        Subject assignee,
        Instant createdAt,
        Instant dueAt) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_INSTRUCTIONS_LENGTH = 4_000;

    public HumanTask {
        Objects.requireNonNull(id, "id");
        title = Checks.requireText("title", title, MAX_TITLE_LENGTH);
        instructions = Checks.requireText("instructions", instructions, MAX_INSTRUCTIONS_LENGTH);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (status.requiresAssignee() && assignee == null) {
            throw new IllegalArgumentException(status + " task needs an assignee");
        }
        if (dueAt != null && !dueAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("dueAt must be after createdAt");
        }
    }
}
