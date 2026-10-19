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

    String target();

    /** Guaranteed key retention after initial dispatch; zero disables automatic replay. */
    default Duration replayWindow() { return Duration.ZERO; }

    Reply execute(String idempotencyKey) throws Exception;

    /** A missing target record is UNKNOWN unless the target can authoritatively prove non-application. */
    default Reply lookup(String idempotencyKey) throws Exception { return new Reply(Outcome.UNKNOWN, null); }
}
