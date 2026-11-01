package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.CheckpointStateIncompatibleException.Reason;
import java.util.Map;
import java.util.Objects;

/**
 * The version of the research checkpoint STATE (what the graph keeps between nodes), separate from the version of the
 * checkpoint TABLES that {@link CheckpointProfileVerifier} pins. A run records {@link #CURRENT} in its state; before a
 * stored run is continued, {@link #requireContinuable} decides whether this build may continue it.
 *
 * <p>The rule is deliberately narrow: a state written by a newer build is refused (this build cannot know what changed),
 * one older than {@link #OLDEST_SUPPORTED} is refused, and an unreadable marker is refused. A state with NO marker was
 * written before versioning existed and is version {@value #LEGACY}, which stays continuable. Raising {@link #CURRENT}
 * requires a compatibility test for every version from {@link #OLDEST_SUPPORTED} up.
 */
public final class ResearchStateVersion {

    /** The state key holding the version. */
    public static final String KEY = "state.version";
    /** The version of states written before the marker existed. */
    public static final int LEGACY = 1;
    /** The version this build writes. */
    public static final int CURRENT = 1;
    /** The oldest version this build continues. */
    public static final int OLDEST_SUPPORTED = 1;

    private ResearchStateVersion() { }

    /** The version a stored state declares. Absent means {@link #LEGACY}. */
    public static int declared(Map<String, Object> state) {
        Object value = Objects.requireNonNull(state, "state").get(KEY);
        if (value == null) return LEGACY;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long version = ((Number) value).longValue();
            if (version >= 1 && version <= Integer.MAX_VALUE) return (int) version;
        }
        throw new CheckpointStateIncompatibleException(Reason.MALFORMED);
    }

    /**
     * @throws CheckpointStateIncompatibleException the stored state is newer, older than supported, or unreadable
     */
    public static void requireContinuable(Map<String, Object> state) {
        requireContinuable(state, CURRENT, OLDEST_SUPPORTED);
    }

    /** The rule against explicit bounds, so a future bump can be tested before the constants move. */
    static void requireContinuable(Map<String, Object> state, int current, int oldestSupported) {
        int version = declared(state);
        if (version > current) throw new CheckpointStateIncompatibleException(Reason.NEWER);
        if (version < oldestSupported) throw new CheckpointStateIncompatibleException(Reason.TOO_OLD);
    }
}
