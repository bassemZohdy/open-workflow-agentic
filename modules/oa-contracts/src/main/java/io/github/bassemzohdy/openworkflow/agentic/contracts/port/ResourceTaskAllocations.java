package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceTaskAllocation;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import java.util.Optional;

/**
 * Durable immutable allocation DATA. Only trusted allocation/approval code supplies candidates; no request-selected
 * task/model/budget/grant. One canonical call retains one exact contract/profile and original lifetime. Repeats
 * compare every field and return the original record, even after expiry/completion; changes conflict. Reads/repeats
 * never renew time, budget or permission. Resolve SDK retry counters to the FIRST canonical scope before calling.
 * A missing/unavailable/inconsistent store refuses new execution; SQL/commit/JTA failure must not be treated as
 * absence. Reconcile the SAME allocation after an uncertain commit, never mint another task/call/profile.
 *
 * <p>Allocation does not reserve model calls, charge/settle usage, authorize direct calls or enter a graph. Private
 * current grant/identity/registry/task/model policy, mandatory audit, one execution entry and durable per-node
 * reservations must all be checked after blocking work before provider calls/output release. No default allowance.
 */
public interface ResourceTaskAllocations {
    AllocatedResourceTask allocate(ResourceTaskAllocation candidate);
    Optional<AllocatedResourceTask> find(ResourceCallScope canonicalScope);
}
