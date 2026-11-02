package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceInvocations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.CompositeExecutionFence;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit candidate identity delegate for one private admitted call and exact credential. The trusted composition
 * root obtains the binding by the witness's canonical key and supplies the server-pinned workload. No bearer is kept.
 * This does not create an entry or check policy, task, target, owner or audit, and is not installed by default.
 * Close only after actual execution terminates; closing also retires the dedicated bound admission witness.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class LiveIdentityResourceFence implements ResourceExecutionFence, AutoCloseable {
    private final AgentResourceAdmission.BoundCall bound;
    private final ResourceExecutionEntry entry;
    private final ResourceCallBinding binding;
    private final Subject workload;
    private final ResourceInvocations invocations;
    private final LiveIdentityCheck live;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LiveIdentityResourceFence(AgentResourceAdmission admission, AgentResourceAdmission.BoundCall bound,
                                     ResourceExecutionEntry entry, ResourceCallBinding binding, Subject workload,
                                     ResourceInvocations invocations) {
        this.bound = Objects.requireNonNull(bound, "bound");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.workload = Objects.requireNonNull(workload, "workload");
        this.invocations = Objects.requireNonNull(invocations, "invocations");
        this.live = new LiveIdentityCheck(Objects.requireNonNull(admission, "admission"));
        if (!entry.equals(bound.entry()) || !binding.scope().equals(entry.scope())
                || !binding.key().equals(bound.bindingKey()))
            throw new IllegalArgumentException("identity attribution differs");
    }

    @Override public void requireCurrent(ResourceExecutionEntry requested) {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw refused("identity context unavailable");
        if (!entry.equals(requested)) throw refused("identity entry differs");
        try {
            var current = invocations.findBinding(bound.bindingKey()).orElseThrow(() -> refused("credential missing"));
            if (!binding.equals(current)) throw refused("credential differs");
            // Compares every binding field to the private verified witness, and rechecks signed time and grants.
            live.requireLive(bound, entry, workload, current);
            if (closed.get() || Thread.currentThread().isInterrupted()) throw refused("identity context unavailable");
            bound.requireEntry(entry); // Closing or expiry may have occurred during the last blocking lookup.
        } catch (IdentityRefusedException refused) {
            throw refused;
        } catch (RuntimeException failure) {
            var refused = refused("identity check unavailable");
            refused.initCause(failure);
            throw refused;
        }
    }

    /**
     * Pairs the same credential with a composite containing this exact identity delegate. The remaining delegates
     * must still be supplied by the trusted composition root; this method does not validate their provenance.
     */
    public JdbcResourceExecutionGate.CredentialFence pair(CompositeExecutionFence composite) {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw refused("identity context unavailable");
        if (!Objects.requireNonNull(composite, "composite").usesIdentity(this))
            throw refused("composite identity differs");
        return JdbcResourceExecutionGate.CredentialFence.pair(binding, composite);
    }

    private static final class IdentityRefusedException extends IllegalStateException {
        IdentityRefusedException(String reason) { super("resource identity refused: " + reason); }
    }
    private static IdentityRefusedException refused(String reason) {
        return new IdentityRefusedException(reason);
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) bound.close(); }
    @Override public String toString() { return "LiveIdentityResourceFence[redacted]"; }
}
