package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.util.Objects;

/**
 * A token was not accepted. The reason is a code, and the message never contains the token or any claim value, so the
 * exception is safe to log. {@link Reason#VERIFIER_UNAVAILABLE} means "could not decide": callers must refuse, exactly
 * as for any other reason.
 */
public final class TokenRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the token was refused. */
    public enum Reason {
        /** No token was presented. */
        MISSING,
        /** Not a JWT, too large, or a claim has the wrong shape. */
        MALFORMED,
        /** The signature does not verify, the algorithm is not allowed, or the key is unknown. */
        SIGNATURE,
        /** The token is past its expiry (plus the allowed clock skew). */
        EXPIRED,
        /** The token is not valid yet, or claims to be issued in the future. */
        NOT_YET_VALID,
        /** The issuer is not the configured one. */
        ISSUER,
        /** The token is not for this audience. */
        AUDIENCE,
        /** A required claim (subject, expiry, tenant, token id) is missing. */
        MISSING_CLAIM,
        /** A delegated token (with an actor) where only a user's own token is accepted, or the reverse. */
        WRONG_TOKEN_KIND,
        /** A single-use token was presented before. */
        REPLAYED,
        /** The delegation grant behind the token is revoked, expired or unknown. */
        GRANT_INACTIVE,
        /** The token lacks a scope the resource requires. */
        SCOPE,
        /** The verifier could not reach its key source or failed. Nothing was decided; the caller must refuse. */
        VERIFIER_UNAVAILABLE
    }

    private final transient Reason reason;

    public TokenRejectedException(Reason reason) {
        super("token rejected: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public TokenRejectedException(Reason reason, Throwable cause) {
        super("token rejected: " + Objects.requireNonNull(reason, "reason"), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
