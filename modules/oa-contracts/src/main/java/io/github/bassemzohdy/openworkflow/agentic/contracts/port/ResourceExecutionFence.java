package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;

/**
 * Private transient runtime guard bound to the EXACT entry. Contains no serializable authority or default allowance.
 * GPT's implementation composes live registry/full owner/definition/task/approved target/execution ownership with
 * Claude's live signed expiry/grant/scope/policy handle. Any refusal or unavailable/missing dependency throws and
 * prevents new work/output. Check after ALL blocking I/O, immediately before entry, EVERY model/tool node and output
 * release; preserve interruption. A registry-only check does not cover revoked/expired identity.
 *
 * <p>The gate still owns the single fresh durable entry transition; a data entry and this interface are not a minted
 * capability. Never accept implementations from request/workflow fields. Async nodes receive the same bound private
 * context across executor threads, never an ambient ThreadLocal; missing/closed context refuses. Keep the context
 * bounded, remove only the same handle after actual work termination, and never persist it in metadata/checkpoints.
 * Cancellation/timeout is not nonapplication; keep committed facts and do not retry UNKNOWN under another key/epoch.
 */
@FunctionalInterface
public interface ResourceExecutionFence {
    void requireCurrent(ResourceExecutionEntry entry);
}
