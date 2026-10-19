package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceCatalog.Entry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generator on Failsafe reports (target/failsafe-reports) and on both report sets of one module. The module is
 * {@code demo}; the integration test is {@code demo.ProcessIT#restarts}, the unit test {@code demo.Suite#passes}.
 * Each test writes its own fixture repository, so no real plan test is involved.
 */
class AcceptanceStatusFailsafeTest {

    private static final String IT_CLASS = "demo.ProcessIT";
    private static final String IT_METHOD = "restarts";

    @TempDir
    Path tmp;

    private Path root;
    private Path module;

    @Test
    void aPassingFailsafeTestIsRecordedAsAutomatedFromItsFailsafeReport() throws Exception {
        repo("{}");
        marker(IT_CLASS, IT_METHOD, "T01");
        failsafe(IT_CLASS, testcase(IT_CLASS, IT_METHOD, ""));

        Run run = run("--root", root.toString());
        assertEquals(0, run.exit, run.err);
        assertEquals(new Entry("automated", "demo:ProcessIT#restarts"), statusOf("T01"));
        assertTrue(run.out.contains("(passing: 1)"), run.out);
    }

    @Test
    void aFailingFailsafeTestIsARegressionAndNothingIsWritten() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:ProcessIT#restarts\"}}";
        repo(before);
        marker(IT_CLASS, IT_METHOD, "T01");
        failsafe(IT_CLASS, testcase(IT_CLASS, IT_METHOD, "<failure message=\"not ready\">trace</failure>"));

        Run run = run("--root", root.toString());
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("regression: T01 is recorded as automated but is not passing: "
                + "demo:ProcessIT#restarts FAILED"), run.err);
        assertEquals(before, read("docs/requirements/test-status.json"));
    }

    @Test
    void aSkippedFailsafeTestIsARegressionEvenWhenTheBuildIsGreen() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:ProcessIT#restarts\"}}";
        repo(before);
        marker(IT_CLASS, IT_METHOD, "T01");
        failsafe(IT_CLASS, testcase(IT_CLASS, IT_METHOD, "<skipped/>"));

        Run run = run("--root", root.toString(), "--check");
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("demo:ProcessIT#restarts SKIPPED"), run.err);
    }

    @Test
    void aFailsafeTestMissingFromItsClassReportIsARegression() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:ProcessIT#restarts\"}}";
        repo(before);
        marker(IT_CLASS, IT_METHOD, "T01");
        failsafe(IT_CLASS, testcase(IT_CLASS, "renamedLater", ""));

        Run run = run("--root", root.toString(), "--check");
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("demo:ProcessIT#restarts MISSING"), run.err);
    }

    @Test
    void anAutomatedFailsafeTestWithoutAnyFailsafeReportIsMissingEvenWhenSurefireRan() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:ProcessIT#restarts\"}}";
        repo(before);
        marker(IT_CLASS, IT_METHOD, "T01");
        surefire("demo.Suite", testcase("demo.Suite", "passes", ""));

        Run run = run("--root", root.toString());
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("regression: T01 is recorded as automated but is not passing: "
                + "demo:ProcessIT#restarts MISSING"), run.err);
    }

    @Test
    void aSurefireAndAFailsafeTestOfTheSameModuleAreBothRecorded() throws Exception {
        repo("{}");
        marker("demo.Suite", "passes", "T01");
        surefire("demo.Suite", testcase("demo.Suite", "passes", ""));
        marker(IT_CLASS, IT_METHOD, "T02");
        failsafe(IT_CLASS, testcase(IT_CLASS, IT_METHOD, ""));

        Run run = run("--root", root.toString());
        assertEquals(0, run.exit, run.err);
        assertEquals(new Entry("automated", "demo:Suite#passes"), statusOf("T01"));
        assertEquals(new Entry("automated", "demo:ProcessIT#restarts"), statusOf("T02"));
    }

    @Test
    void oneTestAnnotatedOnBothASurefireAndAFailsafeMethodIsRecordedWithBothPlaces() throws Exception {
        repo("{}");
        marker("demo.Suite", "passes", "T01");
        surefire("demo.Suite", testcase("demo.Suite", "passes", ""));
        marker(IT_CLASS, IT_METHOD, "T01");
        failsafe(IT_CLASS, testcase(IT_CLASS, IT_METHOD, ""));

        Run run = run("--root", root.toString());
        assertEquals(0, run.exit, run.err);
        Entry entry = statusOf("T01");
        assertEquals("automated", entry.status());
        assertEquals(Set.of("demo:Suite#passes", "demo:ProcessIT#restarts"),
                Arrays.stream(entry.where().split("; ")).collect(Collectors.toSet()));
    }

    @Test
    void aTestInBothReportSetsThatPassesInBothIsRecordedOnceWithOnePlace() throws Exception {
        repo("{}");
        marker("demo.Suite", "passes", "T01");
        surefire("demo.Suite", testcase("demo.Suite", "passes", ""));
        failsafe("demo.Suite", testcase("demo.Suite", "passes", ""));

        Run run = run("--root", root.toString());
        assertEquals(0, run.exit, run.err);
        assertEquals(new Entry("automated", "demo:Suite#passes"), statusOf("T01"));
        assertTrue(run.out.contains("(passing: 1)"), run.out);
    }

    @Test
    void aTestInBothReportSetsThatFailsInOneIsARegression() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:Suite#passes\"}}";
        repo(before);
        marker("demo.Suite", "passes", "T01");
        surefire("demo.Suite", testcase("demo.Suite", "passes", ""));
        failsafe("demo.Suite", testcase("demo.Suite", "passes", "<error message=\"boom\">trace</error>"));

        Run run = run("--root", root.toString());
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("demo:Suite#passes FAILED"), run.err);
        assertEquals(before, read("docs/requirements/test-status.json"));
    }

    @Test
    void aSkipInOneReportSetOfAPassingTestIsARegression() throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:Suite#passes\"}}";
        repo(before);
        marker("demo.Suite", "passes", "T01");
        surefire("demo.Suite", testcase("demo.Suite", "passes", ""));
        failsafe("demo.Suite", testcase("demo.Suite", "passes", "<skipped/>"));

        Run run = run("--root", root.toString(), "--check");
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("demo:Suite#passes SKIPPED"), run.err);
    }

    @Test
    void anAutomatedTestWithAnIntegrationMethodMissingFromAllReportsIsARegressionEvenIfAnotherMethodPasses()
            throws Exception {
        String before = "{\"T01\": {\"status\": \"automated\", \"where\": \"demo:Suite#passes\"}}";
        repo(before);
        marker("demo.Suite", "passes", "T01");
        surefire("demo.Suite", testcase("demo.Suite", "passes", ""));
        marker(IT_CLASS, IT_METHOD, "T01");
        // no ProcessIT report at all: the class did not run under failsafe

        Run run = run("--root", root.toString(), "--check");
        assertEquals(1, run.exit);
        assertTrue(run.err.contains("regression: T01 is recorded as automated but is not passing: "
                + "demo:ProcessIT#restarts MISSING"), run.err);
        assertFalse(run.out.contains("consistent with the build"), run.out);
    }

    @Test
    void aPlannedFailsafeTestThatFailsIsLeftAloneAsForSurefire() throws Exception {
        String before = "{\"T01\": {\"status\": \"planned\", \"where\": \"poc item 4\"}}";
        repo(before);
        marker(IT_CLASS, IT_METHOD, "T01");
        failsafe(IT_CLASS, testcase(IT_CLASS, IT_METHOD, "<failure message=\"x\">trace</failure>"));

        Run run = run("--root", root.toString());
        assertEquals(0, run.exit, run.err);
        assertEquals(new Entry("planned", "poc item 4"), statusOf("T01"));
    }

    // ----- fixture helpers -----

    /** A fixture repository with the real requirements.json and the given test-status.json; module {@code demo}. */
    private void repo(String statusJson) throws IOException {
        root = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(root.resolve("docs/requirements"));
        Files.copy(AcceptanceCatalog.findRepoRoot(Path.of("")).resolve("docs/requirements/requirements.json"),
                root.resolve("docs/requirements/requirements.json"));
        Files.writeString(root.resolve("docs/requirements/test-status.json"), statusJson);
        module = Files.createDirectories(root.resolve("demo"));
    }

    /** A testcase element; {@code body} is empty for a pass, or a failure, error or skipped element. */
    private static String testcase(String className, String name, String body) {
        return "<testcase name=\"" + name + "\" classname=\"" + className + "\" time=\"0.1\">" + body + "</testcase>";
    }

    private void failsafe(String suite, String... testcases) throws IOException {
        report("failsafe-reports", suite, testcases);
    }

    private void surefire(String suite, String... testcases) throws IOException {
        report("surefire-reports", suite, testcases);
    }

    private void report(String directory, String suite, String... testcases) throws IOException {
        Path dir = Files.createDirectories(module.resolve("target").resolve(directory));
        Files.writeString(dir.resolve("TEST-" + suite + ".xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="%s" tests="%d">
                  %s
                </testsuite>
                """.formatted(suite, testcases.length, String.join("\n  ", testcases)));
    }

    /** Records one marker for the method of the class, as the extension does. */
    private void marker(String className, String method, String testId) throws IOException {
        Path dir = Files.createDirectories(module.resolve("target/acceptance-markers"));
        Files.writeString(dir.resolve(className + ".tsv"), method + "\t" + testId + "\t\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    private Entry statusOf(String id) throws IOException {
        JsonNode node = new ObjectMapper().readTree(root.resolve("docs/requirements/test-status.json").toFile()).path(id);
        return new Entry(node.path("status").asText(), node.path("where").asText());
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
