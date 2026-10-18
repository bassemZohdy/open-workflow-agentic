package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import java.io.IOException;

/**
 * Port: where the runtime reads the approved policy state from (M26, M27, ADR 0017). In production this is the
 * approved-state store that the control side publishes to. The runtime never calls the control application; it reads
 * this source when it refreshes.
 *
 * <p><b>Failure semantics.</b> A source that cannot be read throws {@link IOException}, and the runtime keeps the last
 * good snapshot. A source that returns something the runtime does not accept (a hash mismatch, a parse error) is
 * rejected by {@link CedarPolicyDecisionPoint#refresh}, which also keeps the last good snapshot. Neither failure makes
 * the runtime allow anything it did not allow before.
 */
public interface PolicySnapshotSource {

    /** Reads the currently published snapshot. Must not return null. */
    PublishedPolicySnapshot read() throws IOException;
}
