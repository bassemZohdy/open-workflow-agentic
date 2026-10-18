package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatusUpdater.Observation;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatusUpdater.Plan;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatusUpdater.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AcceptanceStatusUpdaterTest {

    /** A catalog that holds the given recorded statuses for the tests T01 to T03 (no repository needed). */
    private static AcceptanceCatalog catalogWith(Map<String, AcceptanceCatalog.Entry> recorded) throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("catalog");
        java.nio.file.Files.createDirectories(root.resolve("docs/requirements"));
        java.nio.file.Files.writeString(root.resolve("docs/requirements/requirements.json"), """
                {"mandatory": [{"id": "M01"}], "high": [], "optional": [], "scenarios": [
                  {"id": "S1", "tests": [{"id": "T01", "verifies": ["M01"]}, {"id": "T02", "verifies": ["M01"]},
                                         {"id": "T03", "verifies": ["M01"]}]}]}
                """);
        StringBuilder status = new StringBuilder("{");
        recorded.forEach((id, e) -> status.append(status.length() > 1 ? "," : "")
                .append("\"").append(id).append("\": {\"status\": \"").append(e.status())
                .append("\", \"where\": \"").append(e.where()).append("\"}"));
        java.nio.file.Files.writeString(root.resolve("docs/requirements/test-status.json"),
                status.append("}").toString());
        return AcceptanceCatalog.load(root);
    }

    private static Observation obs(String id, Verdict v) {
        return new Observation(id, "demo", "demo.Suite", "m" + id, v);
    }

    @Test
    void aPassingPlannedTestIsPromotedToAutomated() throws Exception {
        AcceptanceCatalog c = catalogWith(Map.of("T01", new AcceptanceCatalog.Entry("planned", "poc item 4")));
        Plan plan = AcceptanceStatusUpdater.plan(c, List.of(obs("T01", Verdict.PASSED)));
        assertEquals("automated", plan.statuses().get("T01").status());
        assertEquals(1, plan.changes().size());
        assertTrue(plan.changes().get(0).contains("planned -> automated"), plan.changes().toString());
        assertFalse(plan.hasRegressions());
    }

    @Test
    void aTestWithAFailedObservationIsNotPromotedEvenIfAnotherPassed() throws Exception {
        AcceptanceCatalog c = catalogWith(Map.of());
        Plan plan = AcceptanceStatusUpdater.plan(c, List.of(obs("T02", Verdict.PASSED), obs("T02", Verdict.FAILED)));
        assertFalse(plan.statuses().containsKey("T02"));
        assertFalse(plan.hasPendingChanges());
    }

    @Test
    void aFailedAutomatedTestIsARegressionAndTheStatusIsKept() throws Exception {
        AcceptanceCatalog c = catalogWith(Map.of("T01", new AcceptanceCatalog.Entry("automated", "demo:Suite#m")));
        Plan plan = AcceptanceStatusUpdater.plan(c, List.of(obs("T01", Verdict.FAILED)));
        assertTrue(plan.hasRegressions());
        assertEquals("automated", plan.statuses().get("T01").status());
        assertTrue(plan.regressions().get(0).contains("FAILED"), plan.regressions().toString());
    }

    @Test
    void aMissingObservationOfAnAutomatedTestIsAMissingRegression() throws Exception {
        AcceptanceCatalog c = catalogWith(Map.of("T03", new AcceptanceCatalog.Entry("automated", "demo:Suite#mT03")));
        assertEquals(List.of("T03 is recorded as automated but no annotated test reported it"),
                AcceptanceStatusUpdater.missingAutomated(c, List.of(obs("T01", Verdict.PASSED))));
    }

    @Test
    void partialAndNotStartedAreNeverWrittenByTheGenerator() throws Exception {
        AcceptanceCatalog c = catalogWith(Map.of("T02", new AcceptanceCatalog.Entry("partial", "")));
        Plan failing = AcceptanceStatusUpdater.plan(c, List.of(obs("T02", Verdict.FAILED)));
        assertEquals("partial", failing.statuses().get("T02").status());
        assertFalse(failing.hasRegressions());
    }

    @Test
    void aMovedTestUpdatesWhereWithoutChangingStatus() throws Exception {
        AcceptanceCatalog c = catalogWith(Map.of("T01", new AcceptanceCatalog.Entry("automated", "old:Suite#x")));
        Plan plan = AcceptanceStatusUpdater.plan(c, List.of(obs("T01", Verdict.PASSED)));
        assertEquals(new AcceptanceCatalog.Entry("automated", "demo:Suite#mT01"), plan.statuses().get("T01"));
        assertTrue(plan.changes().get(0).startsWith("T01: where updated"), plan.changes().toString());
    }
}
