package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Decision;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Outcome;
import java.util.Objects;
import java.util.Optional;

/**
 * Shows the authority of a live engine instance current NOW, from the registry alone and without any credential. An
 * unknown or undelegated instance, an unreadable registry, a revoked, expired or ended grant, and an interrupted thread
 * all refuse with an {@link AuthorityLostException}. Used by {@link GovernedAsyncDispatch} before and after each task and
 * by {@link GovernedTaskGuard} for every task of an instance.
 */
public final class CurrentAuthority {

    private final GovernedAsyncDispatch.Registry registry;

    public CurrentAuthority(GovernedAsyncDispatch.Registry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** @throws AuthorityLostException the instance is unknown or undelegated, or its authority is not shown current */
    public InstanceAuthority require(String engineInstanceId) {
        if (engineInstanceId == null || engineInstanceId.isBlank()) throw new AuthorityLostException(Reason.NOT_VERIFIED);
        refuseIfInterrupted();
        InstanceAuthority authority = restore(engineInstanceId);
        requireCurrent(authority);
        return authority;
    }

    InstanceAuthority restore(String engineInstanceId) {
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

    void requireCurrent(InstanceAuthority authority) {
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

    static void refuseIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new AuthorityLostException(Reason.UNAVAILABLE);
    }
}
