package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Optional;

/**
 * Stores agent tasks and their thread ownership (the {@code agent_task} table, WP-105; ADR 0020, decision 1).
 *
 * <p><b>Failure semantics: fail closed.</b>
 * <ul>
 *   <li>A lookup that finds no row returns an empty optional. The runtime treats an empty result as "not yours":
 *       it must refuse to read, write or resume that thread, and must never create a row to fill the gap.</li>
 *   <li>{@link #insert} refuses a duplicate task id or thread id with {@link IllegalStateException}.</li>
 *   <li>{@link #replace} is optimistic: it succeeds only when the stored version equals {@code expectedVersion}.
 *       A mismatch is {@link IllegalStateException}; the caller reloads and decides again.</li>
 *   <li>A store that is unavailable throws. Callers must not treat an exception as "no task".</li>
 * </ul>
 */
public interface TaskStore {

    /**
     * Inserts a new task. The record's version must be 0.
     *
     * @throws IllegalStateException if the task id or the thread id already exists, or the version is not 0
     */
    AgentTaskRecord insert(AgentTaskRecord record);

    /** The task with this id, if any. */
    Optional<AgentTaskRecord> find(Ulid taskId);

    /** The task that owns this checkpoint thread, if any. Checked before every read or resume of the thread. */
    Optional<AgentTaskRecord> findByThread(ThreadId threadId);

    /**
     * Replaces a task if the stored version is {@code expectedVersion}; the new record's version must be
     * {@code expectedVersion + 1}.
     *
     * @throws IllegalStateException if the task is missing, the version differs, or the new version is not the next one
     */
    AgentTaskRecord replace(AgentTaskRecord next, long expectedVersion);
}
