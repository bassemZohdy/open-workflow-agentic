package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed composition of the live checks a resource entry needs before work or output is released. It adds no
 * authority: every category must be supplied by the trusted composition root, and a missing category, a refusal, any
 * unexpected failure or an interrupted thread refuses. There is no default allowance and no partial composition.
 *
 * <p>Checks run in the fixed {@link Check} order and stop at the first refusal. The result is only as strong as the
 * delegates: identity must be the pairing-aware check on the SAME admitted credential, and the gate (not this class)
 * still owns the single fresh durable entry transition. Never build one from HTTP or workflow fields.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class CompositeExecutionFence implements ResourceExecutionFence {
    /** Mandatory check categories, evaluated in declaration order (cheapest authority first, audit last). */
    public enum Check { IDENTITY, POLICY, TASK_AND_TARGET, OWNER_AND_LEASE, AUDIT }

    private final Map<Check, ResourceExecutionFence> checks;

    private CompositeExecutionFence(Map<Check, ResourceExecutionFence> checks) {
        this.checks = checks;
    }

    /** Collects delegates; {@link #build()} refuses unless every {@link Check} is present exactly once. */
    public static final class Builder {
        private final Map<Check, ResourceExecutionFence> checks = new EnumMap<>(Check.class);

        public Builder with(Check check, ResourceExecutionFence delegate) {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(delegate, "delegate");
            if (checks.putIfAbsent(check, delegate) != null)
                throw new IllegalArgumentException("duplicate check " + check);
            return this;
        }

        public CompositeExecutionFence build() {
            for (Check check : Check.values())
                if (!checks.containsKey(check))
                    throw new IllegalStateException("missing mandatory check " + check);
            return new CompositeExecutionFence(new EnumMap<>(checks));
        }
    }

    public static Builder builder() { return new Builder(); }

    @Override public void requireCurrent(ResourceExecutionEntry entry) {
        Objects.requireNonNull(entry, "entry");
        for (Check check : Check.values()) {
            if (Thread.currentThread().isInterrupted())
                throw new JdbcResourceExecutionGate.RefusedException("interrupted before " + check + " check");
            try {
                checks.get(check).requireCurrent(entry);
            } catch (JdbcResourceExecutionGate.RefusedException e) {
                throw e;
            } catch (RuntimeException e) {
                // Unavailable or failing dependencies refuse; detail stays in the cause, not in the message.
                var refused = new JdbcResourceExecutionGate.RefusedException(check + " check did not pass");
                refused.initCause(e);
                throw refused;
            }
        }
        if (Thread.currentThread().isInterrupted())
            throw new JdbcResourceExecutionGate.RefusedException("interrupted after checks");
    }

    @Override public String toString() { return "CompositeExecutionFence[redacted]"; }
}
