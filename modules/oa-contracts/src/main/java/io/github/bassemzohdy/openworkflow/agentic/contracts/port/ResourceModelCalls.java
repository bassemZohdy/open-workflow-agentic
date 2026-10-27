package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Callback-only once gate for a trusted canonical model node. A known fresh committed reservation alone may call
 * authorize once; historical find/repeat never does. No caller budget/cost/profile override or serialized permit.
 * Unknown commit, authorization, provider, deadline, identity or cleanup failure retains the full allowance.
 * Reconcile the SAME node; no automatic retry/refund/reset under another credential/worker/SDK counter.
 *
 * <p>authorize is Claude's private PDP/mandatory-audit preparation. It must return a closure WITHOUT making the
 * provider call; DENY/ERROR throws. The gate refreshes database facts, then the private live authority fence AFTER
 * that blocking preparation, before invoking the synchronous closure once and again before returning private
 * output. No JDBC connection or caller JTA is held across either callback. The owned gateway still enforces one
 * physical attempt, actual model scope/registered approval/price ceiling, transport timeout <= remaining original
 * deadline, no provider/SDK retry and non-bypass. Callback counts do NOT establish actual provider enforcement.
 *
 * <p>Use protectedCallBudgetMicros for this call's PDP budget input, not a reset maximum or a free-balance snapshot.
 * It is conservatively held allowance, NOT exact billed usage; string-only responses cannot invent measured cost.
 * Complete occurrence production, actual private identity propagation and full graph/restore/usage remain gates.
 */
public interface ResourceModelCalls {
    @FunctionalInterface interface Authorization<T> {
        Supplier<T> authorize(ModelCallReservation reservation);
    }
    <T> T invokeOnce(ResourceModelCall call, ResourceExecutionFence current, Authorization<T> authorization);
    /** Exact historical DATA only. Missing/unavailable/corrupt metadata never grants permission. */
    Optional<ModelCallReservation> find(ResourceModelCall call);
}
