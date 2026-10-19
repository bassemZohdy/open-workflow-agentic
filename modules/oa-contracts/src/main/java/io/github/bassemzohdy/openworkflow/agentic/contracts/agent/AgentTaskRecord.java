package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Objects;

/**
 * The stored row of an agent task (the {@code agent_task} table, WP-105). It is the authority for thread ownership:
 * the runtime checks it before any read, write or resume of a checkpoint thread (ADR 0020).
 *
 * <p>Invariants checked on construction: the thread id is the derivation of (tenant, owner, workflow instance), the
 * task id is the contract's id, and the owner is the contract's requesting subject. A row that breaks them cannot be
 * built.
 *
 * @param taskId            the task id, equal to {@code contract.id()}
 * @param tenant            the tenant that owns the task
 * @param owner             the user whose authority the task uses
 * @param workflowInstance  the workflow instance that started the task
 * @param threadId          the checkpoint thread, derived from (tenant, owner, workflow instance)
 * @param contract          the approved contract
 * @param status            the lifecycle state
 * @param version           optimistic-lock version, 0 on insert and incremented on each change
 */
public record AgentTaskRecord(
        Ulid taskId,
        String tenant,
        Subject owner,
        String workflowInstance,
        ThreadId threadId,
        AgentTaskContract contract,
        AgentTaskStatus status,
        long version) {

    public AgentTaskRecord {
        Objects.requireNonNull(taskId, "taskId");
        tenant = Checks.requireText("tenant", tenant, ThreadId.MAX_PART_LENGTH);
        Objects.requireNonNull(owner, "owner");
        workflowInstance = Checks.requireText("workflowInstance", workflowInstance, ThreadId.MAX_PART_LENGTH);
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(status, "status");
        Checks.requireInRange("version", version, 0L, Long.MAX_VALUE);
        if (!taskId.equals(contract.id())) {
            throw new IllegalArgumentException("taskId must equal the contract id");
        }
        if (!owner.equals(contract.requestingSubject())) {
            throw new IllegalArgumentException("owner must equal the contract's requesting subject");
        }
        if (!threadId.equals(ThreadId.derive(tenant, owner.id(), workflowInstance))) {
            throw new IllegalArgumentException("threadId is not derived from tenant, owner and workflow instance");
        }
    }
}
