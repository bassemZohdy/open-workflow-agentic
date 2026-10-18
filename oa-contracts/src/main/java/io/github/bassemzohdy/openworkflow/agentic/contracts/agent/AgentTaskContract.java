package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The approved authority of one agent task (M02, ADR 0021).
 *
 * <p>Permitted tools and data scopes are allow-lists. The default is empty, so nothing is permitted unless it is
 * listed. Limits and the risk tier are mandatory. The requesting subject and the delegation grant tie the task to the
 * user whose authority it uses (M11). The contract does not decide anything by itself; the gateways ask
 * {@link #permitsTool(String)} and {@link #permitsDataScope(String)} as one input to the policy decision.
 *
 * <p>A contract is immutable. The only way to derive another is {@link #narrow}, which can reduce authority and never
 * widen it.
 *
 * @param id                 identifier of this task contract
 * @param goal               the approved goal, non-blank, at most {@link #MAX_GOAL_LENGTH} characters
 * @param permittedTools     tool names the task may call (allow-list)
 * @param permittedDataScopes data scopes the task may read or write (allow-list)
 * @param riskTier           risk classification
 * @param limits             iteration, time and cost bounds
 * @param requestingSubject  the actor on whose behalf the task runs
 * @param delegationGrantId  the delegation grant that carries the requesting subject's authority
 */
public record AgentTaskContract(
        Ulid id,
        String goal,
        Set<String> permittedTools,
        Set<String> permittedDataScopes,
        RiskTier riskTier,
        ExecutionLimits limits,
        Subject requestingSubject,
        Ulid delegationGrantId) {

    public static final int MAX_GOAL_LENGTH = 4_000;
    public static final int MAX_ALLOW_LIST_SIZE = 100;
    /** Format of tool names and data scopes: lower case, starts with a letter, for example {@code tool:weather}. */
    public static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9_.:-]{0,127}");

    public AgentTaskContract {
        Objects.requireNonNull(id, "id");
        goal = Checks.requireText("goal", goal, MAX_GOAL_LENGTH);
        permittedTools = Checks.copyTokens("permittedTools", permittedTools, MAX_ALLOW_LIST_SIZE, TOKEN);
        permittedDataScopes = Checks.copyTokens("permittedDataScopes", permittedDataScopes, MAX_ALLOW_LIST_SIZE, TOKEN);
        Objects.requireNonNull(riskTier, "riskTier");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(requestingSubject, "requestingSubject");
        Objects.requireNonNull(delegationGrantId, "delegationGrantId");
    }

    /** True only when {@code tool} is in the allow-list. Null, blank and unknown names give false. */
    public boolean permitsTool(String tool) {
        return tool != null && permittedTools.contains(tool);
    }

    /** True only when {@code scope} is in the allow-list. Null, blank and unknown scopes give false. */
    public boolean permitsDataScope(String scope) {
        return scope != null && permittedDataScopes.contains(scope);
    }

    /**
     * Derives a child contract for delegation. The goal, the requesting subject and the delegation grant are kept.
     * The child may list fewer tools and scopes, keep or raise the risk tier, and have lower or equal limits.
     *
     * @param childId    identifier of the child contract, different from this one
     * @param tools      tools for the child; each must be permitted here
     * @param dataScopes data scopes for the child; each must be permitted here
     * @param riskTier   the child's risk tier; must be at least this contract's tier
     * @param limits     the child's limits; each must be at most this contract's limits
     * @return a new contract with the narrowed authority
     * @throws PermissionWideningException if any part would widen authority
     */
    public AgentTaskContract narrow(Ulid childId, Set<String> tools, Set<String> dataScopes, RiskTier riskTier,
                                    ExecutionLimits limits) {
        Objects.requireNonNull(childId, "childId");
        if (childId.equals(id)) {
            throw new IllegalArgumentException("a narrowed contract needs its own id");
        }
        Set<String> childTools = Checks.copyTokens("tools", tools, MAX_ALLOW_LIST_SIZE, TOKEN);
        Set<String> childScopes = Checks.copyTokens("dataScopes", dataScopes, MAX_ALLOW_LIST_SIZE, TOKEN);
        Objects.requireNonNull(riskTier, "riskTier");
        Objects.requireNonNull(limits, "limits");

        List<String> extraTools = childTools.stream().filter(t -> !permittedTools.contains(t)).sorted().toList();
        if (!extraTools.isEmpty()) {
            throw new PermissionWideningException("tools not permitted by the parent: " + extraTools);
        }
        List<String> extraScopes = childScopes.stream().filter(s -> !permittedDataScopes.contains(s)).sorted().toList();
        if (!extraScopes.isEmpty()) {
            throw new PermissionWideningException("data scopes not permitted by the parent: " + extraScopes);
        }
        if (!riskTier.atLeast(this.riskTier)) {
            throw new PermissionWideningException("risk tier may not be lowered from " + this.riskTier + " to "
                    + riskTier);
        }
        if (!limits.isWithin(this.limits)) {
            throw new PermissionWideningException("limits exceed the parent's limits: " + limits);
        }
        return new AgentTaskContract(childId, goal, childTools, childScopes, riskTier, limits, requestingSubject,
                delegationGrantId);
    }
}
