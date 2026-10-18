package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;

/**
 * The policy that produced a decision, by the platform's stable policy id and the version of the policy set it was
 * loaded from (ADR 0021: decisions are attributed and versioned, M12). An engine-assigned name such as {@code policy0}
 * is not stable, so the id is mapped by the platform before it reaches this record.
 *
 * @param policyId         stable id of the deciding policy
 * @param policySetVersion version of the policy set that contained it
 */
public record PolicyRef(String policyId, String policySetVersion) {

    /** The built-in rule that turns a failed decision into a refusal (see the fail-closed decorator). */
    public static final PolicyRef FAIL_CLOSED = new PolicyRef("oa.fail-closed", "1");

    public PolicyRef {
        policyId = Checks.requireText("policyId", policyId, 128);
        policySetVersion = Checks.requireText("policySetVersion", policySetVersion, 64);
    }
}
