package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.CheckpointStateIncompatibleException.Reason;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchStateVersionTest {
    private static Map<String, Object> state(Object version) {
        var map = new HashMap<String, Object>();
        map.put(ResearchState.TOPIC, "topic");
        map.put(ResearchState.PLAN, "plan");
        if (version != null) map.put(ResearchStateVersion.KEY, version);
        return map;
    }

    private static Reason refusal(Map<String, Object> state) {
        return assertThrows(CheckpointStateIncompatibleException.class,
                () -> ResearchStateVersion.requireContinuable(state)).reason();
    }

    @Test void aStateWithoutAMarkerIsTheLegacyVersionAndStaysContinuable() {
        assertEquals(ResearchStateVersion.LEGACY, ResearchStateVersion.declared(state(null)));
        assertDoesNotThrow(() -> ResearchStateVersion.requireContinuable(state(null)));
        assertDoesNotThrow(() -> ResearchStateVersion.requireContinuable(Map.of()), "even an empty first-run state");
    }

    @Test void theCurrentVersionIsContinuableInEveryIntegralType() {
        for (Object v : new Object[]{ResearchStateVersion.CURRENT, (long) ResearchStateVersion.CURRENT,
                (short) ResearchStateVersion.CURRENT, (byte) ResearchStateVersion.CURRENT})
            assertDoesNotThrow(() -> ResearchStateVersion.requireContinuable(state(v)), v.getClass().getSimpleName());
    }

    @Test void aNewerStateIsRefusedBecauseThisBuildCannotKnowWhatChanged() {
        assertEquals(Reason.NEWER, refusal(state(ResearchStateVersion.CURRENT + 1)));
        assertEquals(Reason.NEWER, refusal(state(Long.valueOf(Integer.MAX_VALUE))));
    }

    @Test void anUnreadableMarkerIsRefusedNeverTreatedAsLegacy() {
        for (Object bad : new Object[]{"1", 1.0, 1.5f, true, 0, -1, (long) Integer.MAX_VALUE + 1, new Object()})
            assertEquals(Reason.MALFORMED, refusal(state(bad)), String.valueOf(bad));
    }

    @Test void aStateOlderThanTheOldestSupportedIsRefusedAgainstExplicitBounds() {
        var e = assertThrows(CheckpointStateIncompatibleException.class,
                () -> ResearchStateVersion.requireContinuable(state(1), 3, 2));
        assertEquals(Reason.TOO_OLD, e.reason());
        assertDoesNotThrow(() -> ResearchStateVersion.requireContinuable(state(2), 3, 2));
        assertDoesNotThrow(() -> ResearchStateVersion.requireContinuable(state(3), 3, 2));
        assertEquals(Reason.NEWER, assertThrows(CheckpointStateIncompatibleException.class,
                () -> ResearchStateVersion.requireContinuable(state(4), 3, 2)).reason());
    }

    @Test void legacyIsTreatedAsVersionOneWhenTheOldestSupportedMovesUp() {
        assertEquals(Reason.TOO_OLD, assertThrows(CheckpointStateIncompatibleException.class,
                () -> ResearchStateVersion.requireContinuable(state(null), 2, 2)).reason());
    }

    @Test void theRefusalCarriesOnlyAFixedCodeNeverStateValues() {
        var e = assertThrows(CheckpointStateIncompatibleException.class,
                () -> ResearchStateVersion.requireContinuable(state("secret topic text")));
        assertFalse(e.getMessage().contains("secret"));
        assertEquals("the stored checkpoint state cannot be continued: malformed", e.getMessage());
    }

    @Test void theWriterAndTheOldestSupportedAreConsistent() {
        assertTrue(ResearchStateVersion.OLDEST_SUPPORTED <= ResearchStateVersion.LEGACY);
        assertTrue(ResearchStateVersion.LEGACY <= ResearchStateVersion.CURRENT);
    }

    @Test void nullStateIsRejected() {
        assertThrows(NullPointerException.class, () -> ResearchStateVersion.declared(null));
    }
}
