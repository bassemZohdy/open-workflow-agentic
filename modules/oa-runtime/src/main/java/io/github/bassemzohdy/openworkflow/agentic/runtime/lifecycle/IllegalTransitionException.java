package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

/** A status change that the state machine does not allow. It is never retried: the caller has a bug or a stale view. */
public final class IllegalTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(String kind, Enum<?> from, Enum<?> to) {
        super(kind + " may not move from " + from + " to " + to);
    }
}
