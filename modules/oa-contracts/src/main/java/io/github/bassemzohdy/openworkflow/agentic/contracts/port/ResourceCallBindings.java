package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import java.util.Optional;

/**
 * Durable resource-call attribution. No implementation/activation is supplied by this port. GPT supplies the trusted
 * producer/storage/dispatch adapter; Claude supplies verified admission/final current-authority/policy consumption.
 *
 * <p>The producer validates current authoritative registry/full-owner/definition/task attribution, registered resource
 * target and the separate exact V65 issued-grant binding before writing. Bind must atomically retain BOTH the stable
 * invocation contract and the issuer/jti mapping. Existing invocation key with changed scope fingerprint, or issuer/jti
 * with changed binding, is a conflict (IllegalStateException), including a new token used to retarget an old invocation.
 * Exact repeats return the immutable stored binding. Multiple credentials may name the SAME unchanged invocation;
 * that does not permit multiple execution. The execution/effect/result protocol supplies duplicate/unknown-outcome
 * control separately. The binding itself is never a completed call or permission.
 *
 * <p>A successful durable bind/readback must precede release of the transient exchanged bearer. Any storage/audit
 * failure or unknown commit reply refuses release; reconcile/read back the SAME identity, never mint a new invocation.
 * Consumers use verified issuer+jti, compare every stored scope/grant/expiry/request/target field, revalidate current
 * authority and durable replay at admission, then refresh after all blocking work before graph/effect entry. Missing
 * binding, conflicts or dependencies unavailable fail closed. Neither HTTP body/header metadata nor a hash is authority.
 *
 * <p>Rows are retained through credential expiry and outstanding invocation/effect/recovery/audit references; no generic
 * delete or overwrite API is provided. No bearer, raw request body or subject/actor token may be persisted here.
 */
public interface ResourceCallBindings {
    ResourceCallBinding bind(ResourceCallBinding binding);
    Optional<ResourceCallBinding> find(String verifiedIssuer,String verifiedTokenId);
}
