package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Decision;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Outcome;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The governed consumer of identity for one asynchronous task of a live Flow instance (WP-102). It carries the effective
 * user's authority into the task without a bearer ever being stored: for each task it restores the instance's authority
 * from the registry, re-checks the delegation grant NOW, exchanges for a fresh short-lived token for exactly one audience
 * and scope set, runs the work, and checks the authority again before the result is released.
 *
 * <p>Everything fails closed with an {@link AuthorityLostException}: an unknown or undelegated instance, a registry that
 * cannot answer, a grant that is revoked, expired or whose ancestor ended (the instance is then marked for
 * re-authorization), no current subject credential, a refused or unavailable exchange, an interrupted thread, and a
 * revocation or expiry that happens while the work blocks. A work failure is not an authority failure and propagates
 * unchanged. Unlike {@link InstanceAuthorities#restore}, a missing identity never means "run without one".
 *
 * <p>The issued token exists only as the argument of the work callback; it is never a field, never in the
 * {@link AuthorityScope}, and never returned. The scope holds identifiers only.
 */
public final class GovernedAsyncDispatch {

    /** The registry and grant view this dispatch needs; {@link #over} adapts the real components. */
    public interface Registry {
        Optional<InstanceAuthority> find(String engineInstanceId);

        Decision recheck(Ulid instanceId);

        void requireReauthorization(InstanceAuthority authority, String reason);

        Optional<DelegationGrant> grant(Ulid grantId);
    }

    /** Where the process-private credentials come from. They are never persisted; absence means re-authorization. */
    public interface Credentials {
        /** The current subject credential of this instance's user, if this process still holds a usable one. */
        Optional<Credential> subject(InstanceAuthority authority);

        /** The workload (actor) credential of the acting agent. */
        Credential actor();
    }

    private final Registry registry;
    private final Credentials credentials;
    private final TokenExchanger exchanger;
    private final Clock clock;

    public GovernedAsyncDispatch(Registry registry, Credentials credentials, TokenExchanger exchanger, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.exchanger = Objects.requireNonNull(exchanger, "exchanger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Adapts the real registry and grants. */
    public static Registry over(InstanceAuthorities authorities, DelegationGrants grants) {
        Objects.requireNonNull(authorities, "authorities");
        Objects.requireNonNull(grants, "grants");
        return new Registry() {
            @Override public Optional<InstanceAuthority> find(String engineInstanceId) {
                return authorities.findByEngineInstanceId(engineInstanceId);
            }
            @Override public Decision recheck(Ulid instanceId) { return authorities.recheck(instanceId); }
            @Override public void requireReauthorization(InstanceAuthority authority, String reason) {
                authorities.require(authority, reason);
            }
            @Override public Optional<DelegationGrant> grant(Ulid grantId) { return grants.find(grantId); }
        };
    }

    /**
     * Runs {@code work} for the instance the engine knows under {@code engineInstanceId}, with a fresh token for
     * {@code audience} and {@code scopes}.
     *
     * @throws AuthorityLostException the authority could not be shown to be current before or after the work
     */
    public <T> T run(String engineInstanceId, String audience, Set<String> scopes, Function<IssuedToken, T> work) {
        Objects.requireNonNull(work, "work");
        if (engineInstanceId == null || engineInstanceId.isBlank()) throw new AuthorityLostException(Reason.NOT_VERIFIED);
        refuseIfInterrupted();
        InstanceAuthority authority = restore(engineInstanceId);
        requireCurrent(authority);
        IssuedToken token = exchange(authority, audience, scopes);
        T result;
        try (AuthorityScope ignored = AuthorityScope.enter(authority)) {
            result = work.apply(token);
        }
        // Blocking work may have outlived the grant or the token: nothing is released unless both are still current.
        refuseIfInterrupted();
        requireCurrent(authority);
        if (!token.usableAt(clock.instant())) throw new AuthorityLostException(Reason.EXPIRED);
        return result;
    }

    private InstanceAuthority restore(String engineInstanceId) {
        Optional<InstanceAuthority> found;
        try {
            found = registry.find(engineInstanceId);
        } catch (RuntimeException e) {
            throw new AuthorityLostException(Reason.UNAVAILABLE, e);
        }
        InstanceAuthority authority = found.orElseThrow(() -> new AuthorityLostException(Reason.NOT_VERIFIED));
        if (!authority.delegated()) throw new AuthorityLostException(Reason.NOT_VERIFIED);
        return authority;
    }

    private void requireCurrent(InstanceAuthority authority) {
        Decision decision;
        try {
            decision = registry.recheck(authority.instanceId());
        } catch (RuntimeException e) {
            throw new AuthorityLostException(Reason.UNAVAILABLE, e);
        }
        if (decision.outcome() == Outcome.PROCEED) return;
        throw new AuthorityLostException(decision.outcome() == Outcome.REAUTHORIZATION_REQUIRED
                ? Reason.REVOKED : Reason.UNAVAILABLE);
    }

    private IssuedToken exchange(InstanceAuthority authority, String audience, Set<String> scopes) {
        DelegationGrant grant;
        Credential subject;
        ExchangeRequest request;
        try {
            grant = registry.grant(authority.grantId()).orElseThrow(() -> new AuthorityLostException(Reason.REVOKED));
            subject = credentials.subject(authority).orElseThrow(() -> new AuthorityLostException(Reason.EXPIRED));
            request = new ExchangeRequest(subject, credentials.actor(), audience, scopes, grant);
        } catch (AuthorityLostException e) {
            throw e;
        } catch (ExchangeException e) {
            throw new AuthorityLostException(Reason.UNAVAILABLE, e);
        } catch (RuntimeException e) {
            throw new AuthorityLostException(Reason.UNAVAILABLE, e);
        }
        try {
            IssuedToken token = Objects.requireNonNull(exchanger.exchange(request), "exchange result");
            if (!token.usableAt(clock.instant())) throw new AuthorityLostException(Reason.EXPIRED);
            return token;
        } catch (AuthorityLostException e) {
            throw e;
        } catch (ExchangeException e) {
            if (e.code() == ExchangeException.Code.GRANT_INACTIVE) {
                markForReauthorization(authority, "grant.inactive");
                throw new AuthorityLostException(Reason.REVOKED, e);
            }
            throw new AuthorityLostException(Reason.UNAVAILABLE, e);
        } catch (RuntimeException e) {
            throw new AuthorityLostException(Reason.UNAVAILABLE, e);
        }
    }

    private void markForReauthorization(InstanceAuthority authority, String reason) {
        try {
            registry.requireReauthorization(authority, reason);
        } catch (RuntimeException e) {
            // The audit or registry write failed: the step is refused either way and the next pass asks again.
        }
    }

    private static void refuseIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new AuthorityLostException(Reason.UNAVAILABLE);
    }
}
