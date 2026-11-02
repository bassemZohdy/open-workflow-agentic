package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.impl.WorkflowContextData;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local candidate association between one exact SDK context object and its original owner generation.
 * This is not an authority source; only a trusted runtime may bind claims. A live context cannot be rebound to a
 * successor's claim. Weak identity keys avoid retaining finished SDK instances indefinitely. Not wired by default.
 */
@ApplicationScoped
public class WorkflowOwnerContextBindings {
    private static final int LIMIT = 4096;
    private final ReferenceQueue<WorkflowContextData> collected = new ReferenceQueue<>();
    private final Map<IdentityKey, JdbcWorkflowOwnerLease.Claim> claims = new HashMap<>();

    public synchronized void bind(WorkflowContextData context, JdbcWorkflowOwnerLease.Claim claim) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(claim, "claim");
        clean();
        var probe = new IdentityKey(context, null);
        var existing = claims.get(probe);
        if (existing != null) {
            if (!existing.equals(claim)) throw new IllegalStateException("SDK context already has another owner generation");
            return;
        }
        if (claims.size() >= LIMIT) throw new IllegalStateException("workflow owner context capacity exhausted");
        claims.put(new IdentityKey(context, collected), claim);
    }

    public synchronized JdbcWorkflowOwnerLease.Claim require(WorkflowContextData context) {
        Objects.requireNonNull(context, "context"); clean();
        var claim = claims.get(new IdentityKey(context, null));
        if (claim == null) throw new IllegalStateException("SDK context has no trusted owner generation");
        return claim;
    }

    private void clean() {
        IdentityKey key;
        while ((key = (IdentityKey) collected.poll()) != null) claims.remove(key);
    }

    private static final class IdentityKey extends WeakReference<WorkflowContextData> {
        private final int hash;
        IdentityKey(WorkflowContextData value, ReferenceQueue<WorkflowContextData> queue) {
            super(value, queue); hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            var value = get(); return value != null && value == key.get();
        }
    }
}
