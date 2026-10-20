package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bindings in memory, for tests and single-process development. Production uses {@link JdbcIssuedTokenBindings}. */
public final class InMemoryIssuedTokenBindings implements IssuedTokenBindings {

    private final Map<String, Ulid> bound = new ConcurrentHashMap<>();
    private volatile boolean failing;

    @Override
    public void bind(String issuer, String tokenId, Ulid grantId, String tenant, Instant expiresAt) {
        if (failing) {
            throw new IllegalStateException("the token bindings are unavailable");
        }
        Ulid existing = bound.putIfAbsent(issuer + "|" + tokenId, grantId);
        if (existing != null && !existing.equals(grantId)) {
            throw new IllegalStateException("the token is already bound to another grant");
        }
    }

    @Override
    public Optional<Ulid> grantOf(String issuer, String tokenId) {
        if (failing) {
            throw new IllegalStateException("the token bindings are unavailable");
        }
        return Optional.ofNullable(bound.get(issuer + "|" + tokenId));
    }

    /** Makes every call fail, as an outage of the store would. */
    public void fail(boolean failing) {
        this.failing = failing;
    }

    /** Removes a binding, as a lost row would. */
    public void forget(String issuer, String tokenId) {
        bound.remove(issuer + "|" + tokenId);
    }
}
