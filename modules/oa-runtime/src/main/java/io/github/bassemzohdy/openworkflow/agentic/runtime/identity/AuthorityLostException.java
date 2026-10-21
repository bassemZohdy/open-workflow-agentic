package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.util.Objects;

/**
 * A caller's authority could not be shown to be current (WP-108). Every reason is a refusal: the caller must not mutate
 * anything. The message holds only the code, never a claim value or a token.
 */
public final class AuthorityLostException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the authority is not current. */
    public enum Reason {
        /** The identity was not established by a verified token (development headers or a hand-built caller). */
        NOT_VERIFIED,
        /** The signed expiry has passed. */
        EXPIRED,
        /** The token lacks a scope the operation requires. */
        SCOPE,
        /** The issuing grant or an ancestor is revoked, expired or unknown, or no longer matches the token. */
        REVOKED,
        /** The identity dependencies could not decide. Nothing was decided; refuse. */
        UNAVAILABLE
    }

    private final transient Reason reason;

    public AuthorityLostException(Reason reason) {
        super("authority not current: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public AuthorityLostException(Reason reason, Throwable cause) {
        super("authority not current: " + Objects.requireNonNull(reason, "reason"), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
