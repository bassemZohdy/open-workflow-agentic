package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.util.Objects;

/** A token exchange did not produce a usable token. The message never contains a token, a secret or a claim value. */
public final class ExchangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why no token was issued. */
    public enum Code {
        /** The request is malformed: no audience, no scopes, a bad scope token. Nothing was sent. */
        INVALID_REQUEST,
        /** The delegation grant is revoked, expired or unknown. Nothing was sent. Re-authorization is needed. */
        GRANT_INACTIVE,
        /** The scopes asked for are not all in the grant. Nothing was sent. */
        SCOPE_WIDENING,
        /** A subject or actor token does not verify or does not match the grant. Nothing was sent. */
        INPUT_REJECTED,
        /** The authorization server refused with an OAuth error ({@link #oauthError()}). */
        REFUSED,
        /** The authorization server, the network or a needed secret was unavailable. No token was obtained. */
        UNAVAILABLE,
        /** The server answered 200 but the token breaks a rule (refresh token, lifetime, audience, scope, actor). Discarded. */
        BAD_RESPONSE
    }

    private final transient Code code;
    private final String oauthError;
    private final transient GrantCheck.Reason grantReason;

    public ExchangeException(Code code, String message) {
        this(code, message, null, null, null);
    }

    public ExchangeException(Code code, String message, Throwable cause) {
        this(code, message, null, null, cause);
    }

    public ExchangeException(Code code, String message, String oauthError) {
        this(code, message, oauthError, null, null);
    }

    /** A refusal because the grant cannot be used; {@code reason} says why. */
    public ExchangeException(GrantCheck.Reason reason) {
        this(Code.GRANT_INACTIVE, "the delegation grant is not active: " + reason, null, reason, null);
    }

    private ExchangeException(Code code, String message, String oauthError, GrantCheck.Reason grantReason,
                              Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.oauthError = oauthError;
        this.grantReason = grantReason;
    }

    public Code code() {
        return code;
    }

    /** The OAuth {@code error} value of a REFUSED exchange, such as {@code invalid_scope}; otherwise null. */
    public String oauthError() {
        return oauthError;
    }

    /** Why the grant is inactive, for GRANT_INACTIVE; otherwise null. */
    public GrantCheck.Reason grantReason() {
        return grantReason;
    }
}
