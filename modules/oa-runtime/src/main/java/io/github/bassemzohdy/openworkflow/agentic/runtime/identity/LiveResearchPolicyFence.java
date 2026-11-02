package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit research-invocation policy delegate, bound to a private admission witness and one exact entry. The PEP
 * obtains subject and scopes only from that witness and records every decision. This is not current identity,
 * execution ownership, task/target approval or a fresh entry; the trusted composite must supply those separately.
 * The existing research PEP call receives subject and scopes, not tenant or per-task policy facts.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class LiveResearchPolicyFence implements ResourceExecutionFence, AutoCloseable {
    private final AgentResourceAdmission admission;
    private final AgentResourceAdmission.BoundCall bound;
    private final ResourceExecutionEntry entry;
    private final Pep pep;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LiveResearchPolicyFence(AgentResourceAdmission admission, AgentResourceAdmission.BoundCall bound,
                                   ResourceExecutionEntry entry, Pep pep) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.bound = Objects.requireNonNull(bound, "bound");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.pep = Objects.requireNonNull(pep, "pep");
        if (!entry.equals(bound.entry())) throw new IllegalArgumentException("policy entry differs");
    }

    boolean boundTo(AgentResourceAdmission.BoundCall witness, ResourceExecutionEntry expected) {
        return bound == witness && entry.equals(expected) && !closed.get();
    }

    @Override public void requireCurrent(ResourceExecutionEntry requested) {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw refused("policy context unavailable");
        if (!entry.equals(requested)) throw refused("policy entry differs");
        try {
            admission.requireResearchPolicy(bound, entry, pep);
            if (closed.get() || Thread.currentThread().isInterrupted()) throw refused("policy context unavailable");
        } catch (PolicyRefusedException refused) {
            throw refused;
        } catch (RuntimeException failure) {
            var refused = refused("policy check did not allow");
            refused.initCause(failure);
            throw refused;
        }
    }

    private static final class PolicyRefusedException extends IllegalStateException {
        PolicyRefusedException(String reason) { super("resource policy refused: " + reason); }
    }
    private static PolicyRefusedException refused(String reason) { return new PolicyRefusedException(reason); }
    @Override public void close() { closed.set(true); }
    @Override public String toString() { return "LiveResearchPolicyFence[redacted]"; }
}
