package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.time.Instant;

/**
 * Counts how often a token id has been accepted. A resource accepts a delegated token at most {@code maxUses} times;
 * the next use is a replay. Implementations must be atomic: two concurrent uses of a single-use token must not both win.
 * A failure of the cache is an exception, which the guard turns into a refusal (fail closed).
 */
public interface ReplayCache {

    /**
     * Records one use of {@code key} and says whether it was allowed.
     *
     * @param key       issuer and token id
     * @param expiresAt when the token expires; the entry may be dropped some time after
     * @param maxUses   how many uses are allowed (at least 1)
     * @param now       the current time
     * @return true if this use is within {@code maxUses}; false if it is a replay
     */
    boolean use(String key, Instant expiresAt, int maxUses, Instant now);
}
