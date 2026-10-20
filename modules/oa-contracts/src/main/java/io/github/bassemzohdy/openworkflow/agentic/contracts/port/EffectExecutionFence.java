package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectInvocation;

/**
 * Private transient composite authority, never a request callback, checkpoint value or reusable permission.
 * The owned producer checks actual task/occurrence/registry/full owner/target/lease and live verified identity,
 * grant ancestry/expiry/model-or-tool scope and current policy/mandatory decision audit for the exact operation.
 * Compensation requires its own registered target/policy and exact original association. No DEV_HEADERS or
 * UNVERIFIED production fallback. Require freshness AFTER blocking work; store tuples and callback return alone
 * do not establish it. This signature supplies no identity/PDP/lease implementation or physical adapter guarantee.
 */
@FunctionalInterface
public interface EffectExecutionFence {
    enum Boundary { PREPARE, LOOKUP, DISPATCH, RESULT }
    void requireCurrent(EffectInvocation invocation, Boundary boundary);
}
