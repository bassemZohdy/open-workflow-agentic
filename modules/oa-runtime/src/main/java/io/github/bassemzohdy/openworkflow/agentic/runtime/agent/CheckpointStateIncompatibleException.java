package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.util.Objects;

/**
 * A stored research checkpoint carries a state shape this build cannot safely continue. The message and
 * {@link #reason()} are fixed codes: they never carry state values, so they are safe to show. It is a refusal to resume,
 * not "no checkpoint" and not a reason to start the run again under the same thread.
 */
public final class CheckpointStateIncompatibleException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Why the stored state cannot be continued. */
    public enum Reason {
        /** Written by a newer build than this one understands. */
        NEWER("newer"),
        /** Older than the oldest shape this build still continues. */
        TOO_OLD("too-old"),
        /** The version marker is present but not a whole positive number. */
        MALFORMED("malformed");

        private final String code;

        Reason(String code) { this.code = code; }

        public String code() { return code; }
    }

    private final Reason reason;

    CheckpointStateIncompatibleException(Reason reason) {
        super("the stored checkpoint state cannot be continued: " + Objects.requireNonNull(reason, "reason").code());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
