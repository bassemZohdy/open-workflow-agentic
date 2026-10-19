package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.time.Instant;
import java.util.Objects;

/**
 * A policy decision with its attribution (who or what decided), its outcome, the reason and the policy that decided
 * (M12, M15). Every enforcement point records one, allowed or not.
 *
 * @param decidedBy  the component that made the decision, for example {@code cedar-embedded} or {@code fail-closed}
 * @param outcome    ALLOW, DENY or ERROR
 * @param reason     human-readable reason, at most 1000 characters; it must not contain secrets
 * @param policy     the deciding policy and policy-set version
 * @param decidedAt  when the decision was made
 */
public record Decision(String decidedBy, DecisionOutcome outcome, String reason, PolicyRef policy, Instant decidedAt) {

    public static final int MAX_REASON_LENGTH = 1_000;

    public Decision {
        decidedBy = Checks.requireText("decidedBy", decidedBy, 128);
        Objects.requireNonNull(outcome, "outcome");
        reason = Checks.requireText("reason", reason, MAX_REASON_LENGTH);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    /** True only when the outcome is ALLOW. */
    public boolean allows() {
        return outcome.permits();
    }
}
