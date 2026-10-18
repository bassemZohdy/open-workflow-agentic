package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;

/**
 * Decides whether an action may proceed (M12, ADR 0021). Every enforcement point asks this port, and callers never
 * depend on the policy engine behind it.
 *
 * <p><b>Failure semantics: fail closed.</b> Only a {@link Decision} whose outcome is ALLOW lets the action proceed.
 * Every other result is a refusal:
 * <ul>
 *   <li>A normal denial returns {@code DENY} with the deciding policy.</li>
 *   <li>No policy loaded, a policy that does not parse, an engine exception, a non-boolean result, or an empty policy
 *       set returns {@code ERROR}.</li>
 *   <li>A decision that takes longer than the caller's deadline is {@code ERROR}. The implementation must bound its
 *       own time, and {@link FailClosedPolicyDecisionPoint} enforces the deadline for any implementation.</li>
 *   <li>Returning {@code null}, or throwing, is never an allow. Callers should wrap the port in
 *       {@link FailClosedPolicyDecisionPoint}, which turns those cases into {@code ERROR}.</li>
 * </ul>
 * Every decision, including refusals, is recorded in the audit log by the caller (M15).
 */
public interface PolicyDecisionPoint {

    /** Decides one request. Must not return null; see the failure semantics above. */
    Decision decide(AuthorizationRequest request);
}
