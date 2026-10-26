package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.util.Objects;

/**
 * The research checkpoint store is not the pinned public saver profile (or could not be checked). The message and
 * {@link #reason()} are fixed codes: they never carry catalog values, SQL text or database error messages, so they are
 * safe to show. It is a refusal of a dependency, not "no checkpoints" and not a reason to retry the graph.
 */
public final class CheckpointProfileException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final CheckpointProfileVerifier.Reason reason;

    CheckpointProfileException(CheckpointProfileVerifier.Reason reason, Throwable cause) {
        super("the checkpoint store does not match the pinned profile: "
                + Objects.requireNonNull(reason, "reason").code(), cause);
        this.reason = reason;
    }

    public CheckpointProfileVerifier.Reason reason() {
        return reason;
    }
}
