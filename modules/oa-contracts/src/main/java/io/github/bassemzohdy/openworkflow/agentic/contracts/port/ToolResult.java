package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import java.util.Objects;

/**
 * Outcome of a {@link ToolGateway#invoke} call. The three cases are exhaustive, so a caller that switches over the
 * result cannot forget one.
 */
public sealed interface ToolResult permits ToolResult.Completed, ToolResult.Rejected, ToolResult.Failed {

    /** The tool ran and returned {@code output}. */
    record Completed(String output) implements ToolResult {
        public Completed {
            Objects.requireNonNull(output, "output");
        }
    }

    /**
     * The tool did not run. {@code decision} is the refusal: a DENY or ERROR from the policy decision point, or a
     * decision that the gateway made for a tool outside the contract. A rejection never carries an ALLOW.
     */
    record Rejected(Decision decision) implements ToolResult {
        public Rejected {
            Objects.requireNonNull(decision, "decision");
            if (decision.allows()) {
                throw new IllegalArgumentException("a rejection must not carry an ALLOW decision");
            }
        }
    }

    /**
     * The tool was called and did not return a result (timeout, crash, transport error).
     *
     * @param reason                human-readable reason, without secrets
     * @param effectMayHaveHappened true when the tool may have changed an external system; the caller must then
     *                              record the effect as unresolved
     */
    record Failed(String reason, boolean effectMayHaveHappened) implements ToolResult {
        public Failed {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }
}
