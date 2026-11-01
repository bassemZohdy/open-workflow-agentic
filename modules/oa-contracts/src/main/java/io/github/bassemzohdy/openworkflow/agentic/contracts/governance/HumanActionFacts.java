package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.Objects;

/**
 * The retained facts a policy decision about a human-task action is made from (M05, WP-106). It is DATA: it carries no
 * "authorized" flag, no caller-supplied policy answer and no capability, and it is not proof that anyone may act now.
 * The durable human-task record is its only producer; a request body, a header or the visible task text must never
 * build one.
 *
 * <p>Every fact a rule needs is mandatory, so a missing fact cannot turn into permission: there is no null initiator,
 * no absent separation obligation and no default risk. {@link HumanTask} stays a presentation value and does not carry
 * these.
 *
 * @param tenant       the tenant that owns the task
 * @param taskId       the human task
 * @param taskVersion  the version of the task record these facts were read at; the mutation must still match it
 * @param status       the lifecycle state at that version
 * @param dueAt        when the task expires, or null for none
 * @param agentTaskId  the agent task that waits for it, or null when a workflow raised the task
 * @param initiator    who or what caused the task to be raised, retained at creation and never inferred from the assignee
 * @param assignee     the current assignee, or null while the task is open
 * @param riskTier     the risk tier of the approved work the task decides
 * @param separation   the separation-of-duties obligation the approved risk policy attaches to this task
 */
public record HumanActionFacts(
        String tenant,
        Ulid taskId,
        long taskVersion,
        HumanTaskStatus status,
        Instant dueAt,
        Ulid agentTaskId,
        Subject initiator,
        Subject assignee,
        RiskTier riskTier,
        SeparationObligation separation) {

    /** What the approved risk policy requires of who may act, as a retained fact of the task. */
    public enum SeparationObligation {
        /** The policy attaches no separation duty to this task. */
        NONE,
        /** The subject that initiated the work must not decide or route this task. */
        INITIATOR_MUST_NOT_ACT
    }

    public HumanActionFacts {
        tenant = Checks.requireText("tenant", tenant, 256);
        Objects.requireNonNull(taskId, "taskId");
        if (taskVersion < 0) {
            throw new IllegalArgumentException("taskVersion must not be negative");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(riskTier, "riskTier");
        Objects.requireNonNull(separation, "separation");
        if (status.requiresAssignee() && assignee == null) {
            throw new IllegalArgumentException(status + " task needs an assignee");
        }
    }

    @Override
    public String toString() {
        return "HumanActionFacts[task=" + taskId.value() + ", version=" + taskVersion + ", status=" + status + "]";
    }
}
