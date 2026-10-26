package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AgentResourceAdmission.BoundCall;
import java.util.Objects;

/**
 * The synchronous IDENTITY HALF of a future live execution check. It is deliberately not a CDI bean and is activated by
 * nothing: a producer must construct it explicitly with the one real {@link AgentResourceAdmission} that minted the
 * {@link BoundCall}. It is not the composite fence: it knows nothing of the registry, lease, task, approved target, PDP or
 * audit, and grants no callback, fresh-commit or graph permission. A later bounded composite check wraps it and adds those.
 *
 * <p>Each {@link #requireLive} runs on the calling thread (no worker, queue, ThreadLocal or context registry), roots
 * itself in the bound call's private verified token (public {@code Principal}/{@code VerifiedToken} are data and cannot
 * create a check) and re-reads, now: this admission and the call are open and the signed expiry has not passed; the
 * retained entry is exactly the expected one; the exact issuer+jti resource-call binding and grant binding still say what
 * was admitted; the exact issuing grant and every ancestor are active and still match tenant, user, actor chain and scopes;
 * and the issuing grant's typed agent (kind and id) is the expected acting workload. Identity and signed time are
 * rechecked after each blocking read. Any failure, an unavailable store, a closed handle or an interrupt refuses; the
 * interrupt flag is never cleared.
 *
 * <p>A pass is a statement about this instant only: it is not returned, cached or atomic with any other database.
 * Callers invoke it again after PDP, audit or any other I/O and immediately before acting.
 *
 * <p>The expected workload is a required, explicit, SERVER-TRUSTED argument of the producer (for example the workload it
 * dispatched). It is never inferred from the token, grant, request or entry owner, and no mapping exists here.
 */
public final class LiveIdentityCheck {

    private final AgentResourceAdmission admission;

    public LiveIdentityCheck(AgentResourceAdmission admission) {
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    /**
     * @param bound          a call this check's admission minted and bound
     * @param expectedEntry  the exact full retained entry (scope and epoch) the caller holds
     * @param actingWorkload the server-trusted workload that is acting; the full {@link Subject} (kind and id)
     * @throws TokenRejectedException                         a credential, binding, grant or identity store refuses
     * @throws AgentResourceAdmission.WitnessRefusedException closed, wrong minter/entry/workload, or interrupted
     */
    public void requireLive(BoundCall bound, ResourceExecutionEntry expectedEntry, Subject actingWorkload) {
        admission.requireLiveIdentity(bound, expectedEntry, actingWorkload);
    }

    /**
     * The PAIRING-AWARE check production composition must use: everything {@link #requireLive(BoundCall,
     * ResourceExecutionEntry, Subject)} does, and additionally the exact candidate binding, read as DATA from a canonical
     * store (looked up by {@link BoundCall#bindingKey()}, never by HTTP metadata), must equal the bound call's PRIVATE
     * admitted binding in every field (issuer, jti, issuing grant, full scope, issued and expiry). An equal entry or an
     * equal fingerprint alone does not establish that the same credential is meant. A null or different candidate
     * refuses before any store is read. The candidate is not authority and is never retained.
     *
     * @throws AgentResourceAdmission.WitnessRefusedException the candidate is missing or differs, plus every refusal of
     *                                                        the identity-only overload
     */
    public void requireLive(BoundCall bound, ResourceExecutionEntry expectedEntry, Subject actingWorkload,
                            ResourceCallBinding expectedBinding) {
        admission.requireLiveIdentity(bound, expectedEntry, actingWorkload, expectedBinding);
    }
}
