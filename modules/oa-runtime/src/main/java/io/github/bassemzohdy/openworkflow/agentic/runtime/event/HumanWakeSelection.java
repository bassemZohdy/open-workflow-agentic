package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import java.util.Objects;
import java.util.Optional;

/**
 * Combines exact human-wait envelope equality with the supported literal listen-filter subset. This is preparation,
 * not a full SDK predicate, retained-decision verification, an authority check, or permission to match or dispatch.
 * The actual consumer must perform all of those checks and use the owning wait's transactional checkpoint protocol.
 */
public final class HumanWakeSelection {
    private HumanWakeSelection() { }

    public static Optional<SdkListenMatchSet.Selection> select(HumanDecision decision, InboxWait wait,
            InboxListenGroup group, String snapshot, InboxMessage candidate) {
        Objects.requireNonNull(decision); Objects.requireNonNull(wait); Objects.requireNonNull(group);
        Objects.requireNonNull(snapshot); Objects.requireNonNull(candidate);
        if (!group.task().equals(wait.task())) throw new IllegalArgumentException("listen group belongs to another task occurrence");
        if (!HumanDecisionWake.matches(decision, wait, candidate)) return Optional.empty();
        var selected = new SdkListenMatchSet(group, snapshot).matches(candidate);
        // Human decisions are data, never an until/stop event, even if a broad stop filter also matches.
        if (group.classify(selected.slots()).disposition() != InboxListenGroup.Disposition.DATA) return Optional.empty();
        return Optional.of(selected);
    }
}
