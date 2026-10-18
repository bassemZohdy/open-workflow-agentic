package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectKey;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import java.util.List;

/**
 * Records external effects so that each applies once (M03, M06; ADR 0020, decision 6). The protocol is: record the
 * intent, call the target with the idempotency key, record the outcome. After a crash an intent without an outcome is
 * unresolved, and the reconciler asks the target or retries with the same key.
 *
 * <p><b>Failure semantics: fail closed.</b>
 * <ul>
 *   <li>If {@link #recordIntent} fails, the caller must not call the target. No intent means no effect.</li>
 *   <li>If {@link #recordOutcome} fails after the target was called, the intent stays {@code INTENDED}. The caller
 *       must not repeat the external call under a new key; the reconciler resolves the intent.</li>
 *   <li>A conflicting write (same key, different target, or a move the state machine does not allow) is refused with
 *       {@link IllegalStateException}. A key that has no intent gives {@link java.util.NoSuchElementException}.</li>
 * </ul>
 *
 * <p>Repeating a call that is already recorded is idempotent: {@link #recordIntent} returns the stored effect when the
 * target matches, and {@link #recordOutcome} returns the stored effect when it already has that state.
 */
public interface EffectLedger {

    /**
     * Records an intent, which must be in state {@code INTENDED}. If an effect with the same key exists and has the
     * same target, that stored effect is returned unchanged.
     *
     * @throws IllegalArgumentException if the intent is not {@code INTENDED}
     * @throws IllegalStateException    if the key exists with a different target
     */
    Effect recordIntent(Effect intent);

    /**
     * Moves an effect to {@code outcome} and stores {@code detail}. The move must be allowed by
     * {@link EffectState#canMoveTo}. Asking for the state the effect already has returns it unchanged.
     *
     * @throws java.util.NoSuchElementException if no intent has this key
     * @throws IllegalStateException            if the move is not allowed
     */
    Effect recordOutcome(EffectKey key, EffectState outcome, String detail);

    /** Intents of one thread that have no outcome yet, oldest first. An empty list means nothing to reconcile. */
    List<Effect> findUnresolved(ThreadId threadId);
}
