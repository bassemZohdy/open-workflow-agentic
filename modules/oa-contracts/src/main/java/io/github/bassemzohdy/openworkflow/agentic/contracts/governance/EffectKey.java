package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import java.util.Objects;

/**
 * Identity of one effect intent in the ledger: the checkpoint thread, the graph node and the idempotency key
 * (ADR 0020, decision 6). The idempotency key is sent to the target so a retried call does not apply twice.
 *
 * @param threadId       the checkpoint thread that issued the intent
 * @param nodeId         the graph node that issued it
 * @param idempotencyKey the key sent with the call, 1 to {@link #MAX_KEY_LENGTH} characters
 */
public record EffectKey(ThreadId threadId, String nodeId, String idempotencyKey) {

    public static final int MAX_NODE_ID_LENGTH = 128;
    public static final int MAX_KEY_LENGTH = 200;

    public EffectKey {
        Objects.requireNonNull(threadId, "threadId");
        nodeId = Checks.requireText("nodeId", nodeId, MAX_NODE_ID_LENGTH);
        idempotencyKey = Checks.requireText("idempotencyKey", idempotencyKey, MAX_KEY_LENGTH);
    }
}
