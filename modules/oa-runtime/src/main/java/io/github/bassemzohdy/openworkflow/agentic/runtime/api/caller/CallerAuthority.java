package io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller;

import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/**
 * A transient admission handle (WP-108): the caller of one request plus the means to show, right before an effect,
 * that the authority behind the caller is still current. {@link CallerContext} alone cannot do this, because it keeps
 * no signed expiry; a provenance flag set at admission says nothing about a call that blocked for a while.
 *
 * <p>The verified claims are kept privately (the bearer text is never kept) and the handle has no setters, so neither
 * provenance nor scopes can be changed after admission. It must not be put into a command, a checkpoint or a log: it
 * is valid for the request that created it.
 *
 * <p>{@link #requireCurrent} samples the clock when it is called. Call it AFTER all blocking work (policy, audit,
 * storage) and immediately before the mutation. Nothing here mutates anything.
 */
public final class CallerAuthority {

    /** Checks that a delegated token's exact issuing grant chain is still active, without a second replay admission. */
    @FunctionalInterface
    public interface DelegatedRecheck {
        void recheck(VerifiedToken token, Set<String> requiredScopes) throws TokenRejectedException;
    }

    private final CallerContext caller;
    private final VerifiedToken token;
    private final Clock clock;
    private final DelegatedRecheck delegated;

    private CallerAuthority(CallerContext caller, VerifiedToken token, Clock clock, DelegatedRecheck delegated) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.token = token;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delegated = delegated;
    }

    /** A user's or operator's own verified token. */
    public static CallerAuthority ofUserToken(VerifiedToken token, Clock clock) {
        Objects.requireNonNull(token, "token");
        if (token.isDelegated() || token.tenant() == null) {
            throw new IllegalArgumentException("not a user's own token");
        }
        return new CallerAuthority(new CallerContext(token.tenant(), token.subject(), token.scopes(),
                CallerContext.Provenance.VERIFIED_TOKEN), token, clock, null);
    }

    /** A delegated token admitted through the governed path; {@code recheck} judges its grant chain. */
    public static CallerAuthority ofDelegatedToken(VerifiedToken token, Clock clock, DelegatedRecheck recheck) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(recheck, "recheck");
        if (!token.isDelegated() || token.tenant() == null) {
            throw new IllegalArgumentException("not a delegated token");
        }
        return new CallerAuthority(new CallerContext(token.tenant(), token.subject(), token.scopes(),
                CallerContext.Provenance.VERIFIED_TOKEN), token, clock, recheck);
    }

    /** A caller that is not backed by a verified token (development headers, hand-built): never current. */
    public static CallerAuthority unverified(CallerContext caller, Clock clock) {
        return new CallerAuthority(caller, null, clock, null);
    }

    /** The caller this authority is for. */
    public CallerContext caller() {
        return caller;
    }

    /** True when the delegated path (an actor on behalf of a user) admitted this caller. */
    public boolean delegated() {
        return token != null && token.isDelegated();
    }

    /**
     * Shows that the authority is current now, or throws. For a user or operator token this is the signed expiry
     * (strictly in the future) and the required scopes; for a delegated token it is also the exact issuing grant chain.
     * A development-header or hand-built caller is always refused, so production effects and manual release can never
     * rest on it.
     *
     * @throws AuthorityLostException the authority is not current; nothing may be mutated
     */
    public void requireCurrent(Set<String> requiredScopes) {
        Objects.requireNonNull(requiredScopes, "requiredScopes");
        if (token == null || !caller.verified()) {
            throw new AuthorityLostException(AuthorityLostException.Reason.NOT_VERIFIED);
        }
        if (!clock.instant().isBefore(token.expiresAt())) {
            throw new AuthorityLostException(AuthorityLostException.Reason.EXPIRED);
        }
        if (!token.scopes().containsAll(requiredScopes)) {
            throw new AuthorityLostException(AuthorityLostException.Reason.SCOPE);
        }
        if (delegated != null) {
            try {
                delegated.recheck(token, requiredScopes);
            } catch (TokenRejectedException gone) {
                throw new AuthorityLostException(switch (gone.reason()) {
                    case EXPIRED -> AuthorityLostException.Reason.EXPIRED;
                    case SCOPE -> AuthorityLostException.Reason.SCOPE;
                    case VERIFIER_UNAVAILABLE -> AuthorityLostException.Reason.UNAVAILABLE;
                    default -> AuthorityLostException.Reason.REVOKED;
                }, gone);
            } catch (RuntimeException unexpected) {
                throw new AuthorityLostException(AuthorityLostException.Reason.UNAVAILABLE, unexpected);
            }
        }
        // the clock is read once more: a slow recheck must not carry the token past its expiry
        if (!clock.instant().isBefore(token.expiresAt())) {
            throw new AuthorityLostException(AuthorityLostException.Reason.EXPIRED);
        }
    }
}
