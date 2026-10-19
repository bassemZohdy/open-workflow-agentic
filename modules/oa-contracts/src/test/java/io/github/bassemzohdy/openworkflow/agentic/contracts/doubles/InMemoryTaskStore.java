package io.github.bassemzohdy.openworkflow.agentic.contracts.doubles;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.TaskStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Reference task store for tests, with the unique thread constraint and optimistic versions. */
public final class InMemoryTaskStore implements TaskStore {

    private final Map<Ulid, AgentTaskRecord> byId = new HashMap<>();
    private final Map<ThreadId, Ulid> byThread = new HashMap<>();

    @Override
    public synchronized AgentTaskRecord insert(AgentTaskRecord record) {
        if (record.version() != 0) {
            throw new IllegalStateException("a new task starts at version 0");
        }
        if (byId.containsKey(record.taskId()) || byThread.containsKey(record.threadId())) {
            throw new IllegalStateException("task or thread already exists");
        }
        byId.put(record.taskId(), record);
        byThread.put(record.threadId(), record.taskId());
        return record;
    }

    @Override
    public synchronized Optional<AgentTaskRecord> find(Ulid taskId) {
        return Optional.ofNullable(byId.get(taskId));
    }

    @Override
    public synchronized Optional<AgentTaskRecord> findByThread(ThreadId threadId) {
        Ulid taskId = byThread.get(threadId);
        return taskId == null ? Optional.empty() : Optional.ofNullable(byId.get(taskId));
    }

    @Override
    public synchronized AgentTaskRecord replace(AgentTaskRecord next, long expectedVersion) {
        AgentTaskRecord current = byId.get(next.taskId());
        if (current == null) {
            throw new IllegalStateException("no such task");
        }
        if (current.version() != expectedVersion) {
            throw new IllegalStateException("version conflict: stored " + current.version() + ", expected "
                    + expectedVersion);
        }
        if (next.version() != expectedVersion + 1) {
            throw new IllegalStateException("the next version must be " + (expectedVersion + 1));
        }
        if (!next.threadId().equals(current.threadId())) {
            throw new IllegalStateException("a task keeps its thread");
        }
        byId.put(next.taskId(), next);
        return next;
    }
}
