package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bindings in memory, for tests and single-process development; same conflict semantics as
 * {@link JdbcIssuedTokenBindings}. Production uses the JDBC implementation.
 */
public final class InMemoryIssuedTokenBindings implements IssuedTokenBindings {

    private record Key(String issuer, String tokenId) { }

    private final Map<Key, Binding> bound = new ConcurrentHashMap<>();
    private volatile boolean failing;

    @Override
    public void bind(String issuer, String tokenId, Binding binding) {
        check();
        Binding existing = bound.putIfAbsent(new Key(issuer, tokenId), binding);
        if (existing != null && !existing.equals(binding)) {
            throw new IllegalStateException("the token is already bound to a different grant, tenant or expiry");
        }
    }

    @Override
    public Optional<Binding> find(String issuer, String tokenId) {
        check();
        return Optional.ofNullable(bound.get(new Key(issuer, tokenId)));
    }

    private void check() {
        if (failing) {
            throw new IllegalStateException("the token bindings are unavailable");
        }
    }

    /** Makes every call fail, as an outage of the store would. */
    public void fail(boolean failing) {
        this.failing = failing;
    }

    /** Removes a binding, as a lost row would. */
    public void forget(String issuer, String tokenId) {
        bound.remove(new Key(issuer, tokenId));
    }
}
