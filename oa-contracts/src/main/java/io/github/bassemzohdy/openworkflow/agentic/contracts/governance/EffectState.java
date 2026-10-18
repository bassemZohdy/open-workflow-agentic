package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

/**
 * Outcome states of an external effect in the effect ledger (ADR 0020 decision 6, plan section 2).
 *
 * <p>Allowed moves: {@code INTENDED} to {@code CONFIRMED} or {@code FAILED}; {@code CONFIRMED} or {@code FAILED} to
 * {@code COMPENSATED}. {@code FAILED} can still be compensated because a call that reports failure may have applied
 * part of its change. {@code COMPENSATED} is final. An intent that is still {@code INTENDED} after a crash is
 * unresolved and the reconciler must resolve it.
 */
public enum EffectState {
    /** The intent is recorded; the external call may or may not have happened. */
    INTENDED,
    /** The target confirmed the effect. */
    CONFIRMED,
    /** The target refused the effect or it could not be applied. */
    FAILED,
    /** The effect was undone by a compensating action. */
    COMPENSATED;

    /** True when the ledger may move an effect from this state to {@code next}. */
    public boolean canMoveTo(EffectState next) {
        return switch (this) {
            case INTENDED -> next == CONFIRMED || next == FAILED;
            case CONFIRMED, FAILED -> next == COMPENSATED;
            case COMPENSATED -> false;
        };
    }

    /** True when the intent has an outcome and needs no reconciliation. */
    public boolean isResolved() {
        return this != INTENDED;
    }
}
