package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import java.util.Objects;

/**
 * Immutable association of the FIRST canonical resource call with its real approved task/model profile.
 * parentTaskId is nullable only when no parent task exists; a supplied parent must be the actual retained task.
 * Stores validate the real registry, grant ancestry/agent, and parent ownership/narrowing; the trusted producer
 * supplies approval and complete occurrence provenance. No task ownership row or ws1 thread is fabricated here.
 * Credential refresh and execution epochs do not create another allocation or reset any budget/deadline.
 * This value is DATA. Live authority, single execution entry and durable per-node budgets remain separate gates.
 */
public record ResourceTaskAllocation(ResourceCallScope scope, Ulid parentTaskId, AgentTaskContract contract,
                                     ApprovedModelProfile modelProfile) {
    public ResourceTaskAllocation {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(modelProfile, "modelProfile");
        if (!scope.owner().equals(contract.requestingSubject()))
            throw new IllegalArgumentException("allocation task must retain the full resource owner");
        if (contract.id().equals(parentTaskId)) throw new IllegalArgumentException("child allocation cannot reuse its parent task id");
        AgentTaskContractFingerprint.of(contract); // Reject malformed Unicode before hashing or storage.
        modelProfile.requireWithin(contract);
    }

    /** Contract/profile intentionally excluded: a changed approval conflicts at the same allocation identity. */
    public String key() {
        return "ra1-" + CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.task-allocation.v1")
                .text(scope.key()).toBytes());
    }

    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.task-allocation-contract.v1")
                .text(scope.key()).text(scope.fingerprint()).optionalText(parentTaskId == null ? null : parentTaskId.value())
                .text(AgentTaskContractFingerprint.of(contract)).text(modelProfile.fingerprint()).toBytes());
    }
}
