package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the governed model client gets the approved context of the call in progress: the agent identity, the approved
 * task contract, the model the task may use and the budget that is left.
 *
 * <p>This is a seam, not a source. The runtime does not invent a task, a grant or a budget: a deployment supplies this
 * bean from the execution entry that created the approved task, and until one does the default answers
 * {@link Optional#empty()} and every model call is refused before the provider is reached (see
 * {@code docs/handoffs/claude/WP-103-model-call-consumer.md}).
 */
public interface ApprovedModelContext {

    /** The context of the call being made now, or empty when there is none, which refuses the call. */
    Optional<Approved> current();

    /**
     * An approved model call context.
     *
     * @param agent                  the agent identity making the call
     * @param task                   the approved task contract the call belongs to
     * @param modelId                the model the call would reach, as the policy names it
     * @param budgetRemainingMicros  budget left for the task, in micros; not negative
     */
    record Approved(Subject agent, AgentTaskContract task, String modelId, long budgetRemainingMicros) {
        public Approved {
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(modelId, "modelId");
            if (budgetRemainingMicros < 0) {
                throw new IllegalArgumentException("budgetRemainingMicros must not be negative");
            }
        }
    }
}
