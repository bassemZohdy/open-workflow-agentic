package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import java.util.Objects;

/**
 * Outcome of a {@link ModelGateway#complete} call. The three cases are exhaustive.
 */
public sealed interface ModelResult permits ModelResult.Completed, ModelResult.Rejected, ModelResult.Failed {

    /**
     * The model answered.
     *
     * @param text       the response text
     * @param costMicros the cost of the call in millionths of the budget unit, at least 0; charged against the
     *                   task's {@code ExecutionLimits.maxCostMicros}. This must be trusted measured/reported usage,
     *                   never a reservation ceiling or a guessed cost from a string-only response
     */
    record Completed(String text, long costMicros) implements ModelResult {
        public Completed {
            Objects.requireNonNull(text, "text");
            if (costMicros < 0) {
                throw new IllegalArgumentException("costMicros must not be negative");
            }
        }
    }

    /** The model was not called; {@code decision} is the refusal and never an ALLOW. */
    record Rejected(Decision decision) implements ModelResult {
        public Rejected {
            Objects.requireNonNull(decision, "decision");
            if (decision.allows()) {
                throw new IllegalArgumentException("a rejection must not carry an ALLOW decision");
            }
        }
    }

    /** The call was made and produced no answer. */
    record Failed(String reason) implements ModelResult {
        public Failed {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }
}
