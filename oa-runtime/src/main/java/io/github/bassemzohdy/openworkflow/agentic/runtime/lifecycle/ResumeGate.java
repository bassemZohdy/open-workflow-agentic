package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import java.util.Objects;

/**
 * A check the reconciler makes before it lets a restored instance run again (docs/lifecycle.md section 11.8, docs/identity.md
 * section 5). The identity package implements it: an instance that runs under a delegation grant continues only while the
 * grant is still usable. The lifecycle package knows nothing about grants; it only knows the three outcomes.
 *
 * <p>An implementation must fail closed: when it cannot decide, it answers {@link Outcome#UNAVAILABLE}, never PROCEED.
 */
@FunctionalInterface
public interface ResumeGate {

    /** The outcome of the check. */
    enum Outcome {
        /** The instance may continue. */
        PROCEED,
        /** The authority of the instance has ended or must be renewed. The instance waits until a person renews it. */
        REAUTHORIZATION_REQUIRED,
        /** The check could not be made now. The instance stays as it is and the next pass asks again. */
        UNAVAILABLE
    }

    /**
     * @param outcome what to do
     * @param reason  a short machine-readable reason, such as {@code grant.expired}; never a secret or a token
     */
    record Decision(Outcome outcome, String reason) {

        public Decision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
        }

        public static Decision proceed() {
            return new Decision(Outcome.PROCEED, "authorized");
        }

        public boolean proceeds() {
            return outcome == Outcome.PROCEED;
        }
    }

    /** The gate that lets everything proceed: for instances and tests that carry no delegated authority. */
    ResumeGate ALLOW = row -> Decision.proceed();

    /** Decides whether the restored instance of this row may run. */
    Decision check(InstanceRecord row);
}
