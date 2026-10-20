package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;

/**
 * Authoritative persisted checkpoint proof for this exact occurrence AND receipt. A callback/publish future is not
 * proof. The proof must commit atomically with the SDK task-completed checkpoint, after checking cancellation and
 * the current dispatch-attempt fence under the instance/delivery locks. It is immutable historical consumption,
 * even if the instance finishes or is cancelled later. Implementations must allow a fresh read under those locks.
 * No permissive default implementation exists.
 */
@FunctionalInterface
public interface InboxCheckpointProof {
    boolean consumed(InboxWait wait, InboxMessage receipt);
}
