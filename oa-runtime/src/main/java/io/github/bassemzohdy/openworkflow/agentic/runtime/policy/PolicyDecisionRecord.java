package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import java.util.Objects;

/**
 * A policy decision before it is converted to the shared audit event contract: the request it answers, the decision,
 * and whether it came from the safe-recovery rule. When the main policy is unavailable and an operation is retried
 * under the recovery rule, two events are written: the main ERROR (with {@code recoveryPath} false) and the recovery
 * decision (true).
 *
 * @param request      the request that was decided
 * @param decision     the decision, with its attribution
 * @param recoveryPath true when the decision came from the built-in safe-recovery rule
 */
public record PolicyDecisionRecord(AuthorizationRequest request, Decision decision, boolean recoveryPath) {

    public PolicyDecisionRecord {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decision, "decision");
    }
}
