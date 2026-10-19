package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts a policy decision into the shared audit event contract. */
final class PolicyDecisionAuditEvent {

    private PolicyDecisionAuditEvent() {
    }

    static AuditEvent from(PolicyDecisionRecord record) {
        var request = record.request();
        var decision = record.decision();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("policy.outcome", decision.outcome().name());
        attributes.put("policy.decidedBy", decision.decidedBy());
        attributes.put("policy.id", decision.policy().policyId());
        attributes.put("policy.version", decision.policy().policySetVersion());
        attributes.put("policy.action", request.action().name());
        attributes.put("policy.reason", decision.reason().substring(0,
                Math.min(decision.reason().length(), AuditEvent.MAX_TEXT_LENGTH)));
        attributes.put("policy.recoveryPath", Boolean.toString(record.recoveryPath()));
        if (request.taskId() != null) {
            attributes.put("policy.taskId", request.taskId().value());
        }
        return AuditDraft.of(UlidGenerator.shared().next(), decision.decidedAt(), request.subject(),
                "policy.decision", "policy-resource", request.resource(), attributes);
    }
}
