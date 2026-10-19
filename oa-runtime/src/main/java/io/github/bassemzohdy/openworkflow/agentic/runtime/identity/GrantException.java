package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.util.Objects;

/** A grant operation was refused. The message never contains a token. */
public final class GrantException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the operation was refused. */
    public enum Code {
        /** The request is malformed: no scopes, a bad scope, an expiry in the past or beyond the maximum. */
        INVALID_REQUEST,
        /** The actor may not do this: only the user gives a root grant, only the parent's agent delegates. */
        FORBIDDEN,
        /** No such grant. */
        NOT_FOUND,
        /** The parent grant is revoked, expired or unknown, so nothing can be delegated from it. */
        PARENT_INACTIVE,
        /** The grant named for an instance is revoked, expired or unknown (use {@code NOT_FOUND} for unknown ids). */
        INACTIVE,
        /** The agent already appears in the delegation chain, or the chain is too deep. */
        CYCLE,
        /** The grant store or the audit trail is unavailable; nothing was granted. */
        UNAVAILABLE
    }

    private final transient Code code;

    public GrantException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public GrantException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
