package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Set;

/**
 * The facts about a call that a policy may read besides who asks and what for (M02, M12). Built by the enforcement
 * point from the approved {@code AgentTaskContract} and from the call itself. Every field is optional except the
 * scopes, which default to none. A null field means "not applicable to this call", and a policy must treat it as
 * the absence of the fact.
 *
 * @param scopes                 scope tokens the caller holds for this call: data scopes the task was approved for,
 *                               or the scopes of an operator or publisher
 * @param riskTier              risk tier of the agent task, or null outside an agent task
 * @param budgetRemainingMicros cost budget left in the task, in micro-units, or null when no budget applies
 * @param onBehalfOf            the user whose authority the call uses, or null
 * @param delegationGrantId     the delegation grant that carries that authority, or null
 * @param permittedByContract   whether the task's allow-list names this call's tool; null when the contract has no
 *                              allow-list for this kind of call (models)
 */
public record PolicyContext(
        Set<String> scopes,
        RiskTier riskTier,
        Long budgetRemainingMicros,
        Subject onBehalfOf,
        Ulid delegationGrantId,
        Boolean permittedByContract) {

    /** No facts beyond the request itself. */
    public static final PolicyContext EMPTY = new PolicyContext(Set.of(), null, null, null, null, null);

    public PolicyContext {
        scopes = Checks.copyTokens("scopes", scopes, AgentTaskContract.MAX_ALLOW_LIST_SIZE, AgentTaskContract.TOKEN);
        if (budgetRemainingMicros != null) {
            Checks.requireInRange("budgetRemainingMicros", budgetRemainingMicros.longValue(), 0,
                    ExecutionLimits.MAX_COST_MICROS);
        }
    }
}
