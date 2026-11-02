package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import java.time.Duration;
import java.util.Objects;

/**
 * Trusted, governed adapter for an external effect. Implementations enforce authorization, target binding and bounded
 * network timeouts. A target may claim a replay window only when it atomically deduplicates the supplied key and
 * identical request throughout that window. Lookup must be read-only and authoritative. UNKNOWN is the safe default.
 */
public interface EffectTarget {
    enum Outcome { CONFIRMED, FAILED, UNKNOWN }

    /** FAILED is proof of refusal/non-application, never an inferred timeout. Detail is a safe, non-secret summary. */
    record Reply(Outcome outcome, String detail) {
        public Reply {
            Objects.requireNonNull(outcome, "outcome");
            if (detail != null && detail.length() > Effect.MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException("effect detail is too long");
            }
        }
    }

    /**
     * A trusted adapter observed a definitive outcome but may not release a result, for example because authority
     * expired after the target replied. The ledger must retain the non-secret fact before propagating the refusal.
     * This is not an authorization, payload, or proof that arbitrary adapter claims are true.
     */
    final class ObservedOutcomeException extends RuntimeException {
        private final Reply observed;
        private final RuntimeException refusal;
        public ObservedOutcomeException(Reply observed, RuntimeException refusal) {
            super("effect outcome observed but result withheld",Objects.requireNonNull(refusal,"refusal"));
            this.observed=Objects.requireNonNull(observed,"observed");
            if(observed.outcome()==Outcome.UNKNOWN)throw new IllegalArgumentException("unknown is not a definitive outcome");
            this.refusal=refusal;
        }
        public Reply observed() {return observed;}
        public RuntimeException refusal() {return refusal;}
    }

    String target();

    /**
     * Conservative replay window after initial dispatch; zero disables automatic replay. Implementations must leave
     * enough margin for clock skew and the maximum delay until the target checks its deduplication record. Starting
     * an outbound call before this deadline alone does not ensure that it reaches the target before retention ends.
     */
    default Duration replayWindow() { return Duration.ZERO; }

    Reply execute(String idempotencyKey) throws Exception;

    /** A missing target record is UNKNOWN unless the target can authoritatively prove non-application. */
    default Reply lookup(String idempotencyKey) throws Exception { return new Reply(Outcome.UNKNOWN, null); }
}
