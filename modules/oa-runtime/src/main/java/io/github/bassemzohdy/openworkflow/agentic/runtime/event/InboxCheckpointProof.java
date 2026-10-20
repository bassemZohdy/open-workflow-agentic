package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;

/**
 * Authoritative persisted checkpoint proof for this exact occurrence AND receipt. A callback/publish future is not
 * proof. No permissive default implementation exists; the SDK persistence adapter must supply and verify it.
 */
@FunctionalInterface
public interface InboxCheckpointProof {
    boolean consumed(InboxWait wait, InboxMessage receipt);
}
