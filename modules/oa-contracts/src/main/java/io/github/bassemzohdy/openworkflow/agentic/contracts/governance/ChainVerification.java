package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

/**
 * Result of {@link AuditHash#verifyChain(java.util.List)}.
 *
 * @param valid           true when every link and every hash holds
 * @param firstInvalidIndex index in the list of the first event that fails, or -1 when valid; equal to the list size
 *                        when the chain is intact but does not end at the expected head (truncation or extension)
 * @param reason          why verification failed, or null when valid
 */
public record ChainVerification(boolean valid, int firstInvalidIndex, String reason) {

    public ChainVerification {
        if (valid) {
            if (firstInvalidIndex != -1 || reason != null) {
                throw new IllegalArgumentException("a valid chain has no failing index or reason");
            }
        } else if (firstInvalidIndex < 0 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an invalid chain needs an index and a reason");
        }
    }

    /** A verification that passed. */
    public static ChainVerification intact() {
        return new ChainVerification(true, -1, null);
    }

    /** A verification that failed at {@code index}. */
    public static ChainVerification broken(int index, String reason) {
        return new ChainVerification(false, index, reason);
    }
}
