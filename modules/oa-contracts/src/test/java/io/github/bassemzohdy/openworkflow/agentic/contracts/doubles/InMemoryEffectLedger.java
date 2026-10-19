package io.github.bassemzohdy.openworkflow.agentic.contracts.doubles;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectKey;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Reference ledger for tests: proves the port can be implemented with the documented semantics. */
public final class InMemoryEffectLedger implements EffectLedger {

    private final Map<EffectKey, Effect> effects = new LinkedHashMap<>();

    @Override
    public synchronized Effect recordIntent(Effect intent) {
        if (intent.state() != EffectState.INTENDED) {
            throw new IllegalArgumentException("an intent must be INTENDED, was " + intent.state());
        }
        Effect existing = effects.get(intent.key());
        if (existing != null) {
            if (!existing.target().equals(intent.target())) {
                throw new IllegalStateException("idempotency key already used for another target");
            }
            return existing;
        }
        effects.put(intent.key(), intent);
        return intent;
    }

    @Override
    public synchronized Effect recordOutcome(EffectKey key, EffectState outcome, String detail) {
        Effect current = effects.get(key);
        if (current == null) {
            throw new NoSuchElementException("no intent for " + key.nodeId());
        }
        if (current.state() == outcome) {
            return current;
        }
        if (!current.state().canMoveTo(outcome)) {
            throw new IllegalStateException(current.state() + " cannot move to " + outcome);
        }
        Effect next = new Effect(key.threadId(), key.nodeId(), key.idempotencyKey(), current.target(), outcome,
                detail, Instant.now());
        effects.put(key, next);
        return next;
    }

    @Override
    public synchronized List<Effect> findUnresolved(ThreadId threadId) {
        List<Effect> unresolved = new ArrayList<>();
        for (Effect effect : effects.values()) {
            if (effect.threadId().equals(threadId) && !effect.state().isResolved()) {
                unresolved.add(effect);
            }
        }
        return unresolved;
    }
}
