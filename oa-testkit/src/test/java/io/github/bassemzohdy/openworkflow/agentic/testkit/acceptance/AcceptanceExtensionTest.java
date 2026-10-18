package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.testkit.engine.EngineTestKit.engine;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures.ClassLevelFixture;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures.ParameterizedFixture;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures.UnknownIdFixture;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures.UnknownRequirementFixture;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures.UnverifiedRequirementFixture;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures.ValidFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Runs fixture classes through the JUnit engine and checks what the extension does to them. The fixtures are not
 * named like surefire tests, so only this class runs them. Markers go to a temporary directory.
 */
class AcceptanceExtensionTest {

    @TempDir
    Path markers;

    @Test
    void validAnnotationPassesAndIsRecordedAsMarker() throws Exception {
        String previous = System.getProperty(AcceptanceExtension.MARKERS_PROPERTY);
        System.setProperty(AcceptanceExtension.MARKERS_PROPERTY, markers.toString());
        try {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(ValidFixture.class)).execute()
                    .testEvents().assertStatistics(s -> s.succeeded(1).failed(0));
            List<AcceptanceMarkers.Marker> recorded = AcceptanceMarkers.read(markers);
            assertEquals(1, recorded.size());
            assertEquals(new AcceptanceMarkers.Marker(ValidFixture.class.getName(), "passes", "T01", List.of("M02")),
                    recorded.get(0));
        } finally {
            restore(previous);
        }
    }

    @Test
    void classLevelAnnotationAppliesToItsMethods() throws Exception {
        String previous = System.getProperty(AcceptanceExtension.MARKERS_PROPERTY);
        System.setProperty(AcceptanceExtension.MARKERS_PROPERTY, markers.toString());
        try {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(ClassLevelFixture.class)).execute()
                    .testEvents().assertStatistics(s -> s.succeeded(1));
            assertEquals("T02", AcceptanceMarkers.read(markers).get(0).testId());
        } finally {
            restore(previous);
        }
    }

    @Test
    void unknownTestIdFailsTheTest() {
        assertFailsWith(UnknownIdFixture.class, "unknown acceptance test id 'T999'");
    }

    @Test
    void unknownRequirementFailsTheTest() {
        assertFailsWith(UnknownRequirementFixture.class, "unknown requirement 'M99'");
    }

    @Test
    void requirementNotVerifiedByTheTestFailsTheTest() {
        assertFailsWith(UnverifiedRequirementFixture.class, "does not verify requirement M29");
    }

    @Test
    void parameterizedTestFailsTheTest() {
        // one failure per invocation of the template (two values)
        assertFailsWith(ParameterizedFixture.class, 2, "not supported on a parameterized test");
    }

    private static void assertFailsWith(Class<?> fixture, String messagePart) {
        assertFailsWith(fixture, 1, messagePart);
    }

    private static void assertFailsWith(Class<?> fixture, int failures, String messagePart) {
        var results = engine("junit-jupiter").selectors(selectClass(fixture)).execute();
        results.testEvents().assertStatistics(s -> s.failed(failures));
        List<String> messages = results.testEvents().failed().stream()
                .map(e -> e.getPayload(TestExecutionResult.class)
                        .flatMap(TestExecutionResult::getThrowable).map(Throwable::getMessage).orElse(""))
                .toList();
        assertTrue(messages.stream().allMatch(m -> m.contains(messagePart)), "messages were: " + messages);
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(AcceptanceExtension.MARKERS_PROPERTY);
        } else {
            System.setProperty(AcceptanceExtension.MARKERS_PROPERTY, previous);
        }
    }
}
