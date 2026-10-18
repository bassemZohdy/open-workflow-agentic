package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceCatalog.Entry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests of the generator on a fixture repository: real requirements.json, a test-status.json written
 * by each test, and surefire reports and markers under a module directory.
 */
class AcceptanceStatusTest {

    private static final String PASSING_TESTCASE = "<testcase name=\"passes\" classname=\"demo.Suite\" time=\"0.1\"/>";
    private static final String FAILING_TESTCASE = "<testcase name=\"passes\" classname=\"demo.Suite\" time=\"0.1\">"
            + "<failure message=\"boom\">boom</failure></testcase>";

    @TempDir
    Path tmp;

    private Path root;
    private Path module;

    @Test
    void updateRecordsAPassingAnnotatedTestAndCheckThenAgrees() throws Exception {
        setUp("{}", PASSING_TESTCASE, marker("T01"));
        Run first = run("--root", root.toString());
        assertEquals(0, first.exit, first.err);
        assertEquals(new Entry("automated", "demo:Suite#passes"), statusOf("T01"));

        Run check = run("--root", root.toString(), "--check");
        assertEquals(0, check.exit, check.err);
        assertTrue(check.out.contains("consistent with the build"), check.out);
    }

    @Test
    void writesTheFileInTheCommittedLayout() throws Exception {
        setUp("{}", PASSING_TESTCASE, marker("T01"));
        run("--root", root.toString());
        String text = Files.readString(root.resolve("docs/requirements/test-status.json"));
        assertEquals("{\n  \"T01\": {\n    \"status\": \"automated\",\n    \"where\": \"demo:Suite#passes\"\n  }\n}\n", text);
    }

    @Test
    void checkFailsWhenAPassingTestIsNotYetRecordedAndWritesNothing() throws Exception {
        String before = "{\"T01\": {\"status\": \"planned\", \"where\": \"poc item 4\"}}";
        setUp(before, PASSING_TESTCASE, marker("T01"));
        Run check = run("--root", root.toString(), "--check");
        assertEquals(1, check.exit);
        assertTrue(check.err.contains("out of date"), check.err);
        assertEquals(before, Files.readString(root.resolve("docs/requirements/test-status.json")));
    }

    @Test
    void plannedTestThatFailsIsLeftAlone() throws Exception {
        String before = "{\"T01\": {\"status\": \"planned\", \"where\": \"poc item 4\"}}";
        setUp(before, FAILING_TESTCASE, marker("T01"));
        Run run = run("--root", root.toString());
        assertEquals(0, run.exit, run.err);
        assertEquals(new Entry("planned", "poc item 4"), statusOf("T01"));
    }

    @Test
    void automatedTestThatFailsIsARegressionAndNothingIsWritten() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:Suite#passes\"}}";
        setUp(before, FAILING_TESTCASE, marker("T01"));
        Run run = run("--root", root.toString());
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("regression: T01"), run.err);
        assertEquals(before, Files.readString(root.resolve("docs/requirements/test-status.json")));
    }

    @Test
    void automatedTestWithoutAnyMarkerIsMissingAndARegression() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:Suite#passes\"}}";
        setUp(before, PASSING_TESTCASE, "");
        Run run = run("--root", root.toString());
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("no annotated test reported it"), run.err);
    }

    @Test
    void markerWhoseTestCaseDidNotRunIsMissingAndARegression() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:Suite#passes\"}}";
        setUp(before, PASSING_TESTCASE, marker("T01") + "renamed\tT01\t\n");
        Run run = run("--root", root.toString());
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("renamed MISSING"), run.err);
    }

    @Test
    void automatedTestThatIsSkippedIsARegression() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:Suite#passes\"}}";
        String skipped = "<testcase name=\"passes\" classname=\"demo.Suite\"><skipped/></testcase>";
        setUp(before, skipped, marker("T01"));
        assertEquals(1, run("--root", root.toString()).exit);
    }

    @Test
    void markerOfAClassWithNoReportEntryIsCountedAndAPlannedTestIsLeftAlone() throws Exception {
        setUp("{}", PASSING_TESTCASE, "");
        Files.createDirectories(module.resolve("target/acceptance-markers"));
        Files.writeString(module.resolve("target/acceptance-markers/demo.NotRun.tsv"), "passes\tT02\t\n");
        Run run = run("--root", root.toString());
        assertEquals(0, run.exit, run.err);
        assertTrue(run.out.contains("no entry in any surefire or failsafe report: 1"), run.out);
        assertFalse(statusRecorded("T02"));
    }

    @Test
    void automatedTestWhoseClassHasNoReportEntryIsMissingAndARegression() throws Exception {
        String before = "{\"T02\": {\"status\": \"automated\", \"where\": \"demo:NotRun#passes\"}}";
        setUp(before, PASSING_TESTCASE, "");
        Files.createDirectories(module.resolve("target/acceptance-markers"));
        Files.writeString(module.resolve("target/acceptance-markers/demo.NotRun.tsv"), "passes\tT02\t\n");
        Run run = run("--root", root.toString(), "--check");
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("regression: T02 is recorded as automated but is not passing: demo:NotRun#passes MISSING"),
                run.err);
        assertEquals(before, Files.readString(root.resolve("docs/requirements/test-status.json")));
    }

    @Test
    void noReportsIsAUsageError() throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Files.createDirectories(empty.resolve("docs/requirements"));
        Files.copy(realRequirements(), empty.resolve("docs/requirements/requirements.json"));
        Files.writeString(empty.resolve("docs/requirements/test-status.json"), "{}");
        assertEquals(2, run("--root", empty.toString()).exit);
    }

    @Test
    void unknownArgumentIsAUsageError() {
        assertEquals(2, run("--frobnicate").exit);
    }

    @Test
    void renderedStatusesParseBackToTheSameEntries() throws IOException {
        Map<String, Entry> entries = Map.of("T12", new Entry("planned", "poc item 4"),
                "T01", new Entry("automated", "a \"quoted\" where"));
        JsonNode back = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(AcceptanceStatus.render(entries));
        assertEquals("automated", back.path("T01").path("status").asText());
        assertEquals("a \"quoted\" where", back.path("T01").path("where").asText());
        assertEquals("planned", back.path("T12").path("status").asText());
        assertEquals(2, back.size());
    }

    private void setUp(String statusJson, String testcase, String markerLines) throws IOException {
        root = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(root.resolve("docs/requirements"));
        Files.copy(realRequirements(), root.resolve("docs/requirements/requirements.json"));
        Files.writeString(root.resolve("docs/requirements/test-status.json"), statusJson);
        module = Files.createDirectories(root.resolve("demo"));
        Path reports = Files.createDirectories(module.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-demo.Suite.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="demo.Suite" tests="1">
                  %s
                </testsuite>
                """.formatted(testcase));
        if (!markerLines.isEmpty()) {
            Path markers = Files.createDirectories(module.resolve("target/acceptance-markers"));
            Files.writeString(markers.resolve("demo.Suite.tsv"), markerLines);
        }
    }

    /** One marker line for method "passes" of demo.Suite with the given test id. */
    private static String marker(String testId) {
        return "passes\t" + testId + "\t\n";
    }

    private static Path realRequirements() throws IOException {
        return AcceptanceCatalog.findRepoRoot(Path.of("")).resolve("docs/requirements/requirements.json");
    }

    private Entry statusOf(String id) throws IOException {
        JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(root.resolve("docs/requirements/test-status.json").toFile()).path(id);
        return new Entry(node.path("status").asText(), node.path("where").asText());
    }

    private boolean statusRecorded(String id) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(root.resolve("docs/requirements/test-status.json").toFile()).has(id);
    }

    private record Run(int exit, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = AcceptanceStatus.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
