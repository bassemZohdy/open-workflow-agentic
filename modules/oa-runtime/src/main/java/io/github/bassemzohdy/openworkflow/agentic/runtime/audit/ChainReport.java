package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

/**
 * The result of verifying one chain.
 *
 * @param chainId              the chain
 * @param intact               true when every link, hash, sequence and the recorded head hold
 * @param eventCount           number of events read
 * @param firstBrokenSequence  the first sequence that does not verify, or null when intact
 * @param firstBrokenEventId   the event at that position, when one exists, or null
 * @param reason               why verification failed, or null when intact
 */
public record ChainReport(String chainId, boolean intact, long eventCount, Long firstBrokenSequence,
                          String firstBrokenEventId, String reason) {

    static ChainReport intact(String chainId, long eventCount) {
        return new ChainReport(chainId, true, eventCount, null, null, null);
    }

    static ChainReport broken(String chainId, long eventCount, long firstBrokenSequence, String firstBrokenEventId,
                              String reason) {
        return new ChainReport(chainId, false, eventCount, firstBrokenSequence, firstBrokenEventId, reason);
    }
}
