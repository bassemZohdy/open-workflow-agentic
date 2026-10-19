package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of the effect ledger: an intent to change an external system, its idempotency key, its target and its
 * current outcome state. The ledger is the authority for side effects (plan section 2, M03, M06).
 *
 * @param threadId       the checkpoint thread that issued the intent
 * @param nodeId         the graph node that issued it
 * @param idempotencyKey the key sent to the target
 * @param target         the system called, for example {@code payments-api:refund}
 * @param state          the current outcome state
 * @param detail         the target's response or the failure reason, or null; at most 1000 characters
 * @param recordedAt     when the state last changed
 */
public record Effect(
        ThreadId threadId,
        String nodeId,
        String idempotencyKey,
        String target,
        EffectState state,
        String detail,
        Instant recordedAt) {

    public static final int MAX_TARGET_LENGTH = 256;
    public static final int MAX_DETAIL_LENGTH = 1_000;

    public Effect {
        Objects.requireNonNull(threadId, "threadId");
        nodeId = Checks.requireText("nodeId", nodeId, EffectKey.MAX_NODE_ID_LENGTH);
        idempotencyKey = Checks.requireText("idempotencyKey", idempotencyKey, EffectKey.MAX_KEY_LENGTH);
        target = Checks.requireText("target", target, MAX_TARGET_LENGTH);
        Objects.requireNonNull(state, "state");
        if (detail != null && detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("detail longer than " + MAX_DETAIL_LENGTH + " characters");
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    /** The ledger key of this effect. */
    public EffectKey key() {
        return new EffectKey(threadId, nodeId, idempotencyKey);
    }
}
