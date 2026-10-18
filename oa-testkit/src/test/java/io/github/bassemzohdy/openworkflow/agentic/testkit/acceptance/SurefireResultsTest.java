package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.SurefireResults.Outcome;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.SurefireResults.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireResultsTest {

    @TempDir
    Path reports;

    @Test
    void readsPassedFailedErroredAndSkippedTestcases() throws IOException {
        Files.writeString(reports.resolve("TEST-a.B.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="a.B" tests="4">
                  <properties><property name="java.version" value="21"/></properties>
                  <testcase name="ok" classname="a.B" time="0.01"/>
                  <testcase name="bad" classname="a.B" time="0.01"><failure message="x">trace</failure></testcase>
                  <testcase name="broken" classname="a.B" time="0.01"><error message="y">trace</error></testcase>
                  <testcase name="later" classname="a.B" time="0.01"><skipped/></testcase>
                </testsuite>
                """);
        SurefireResults.Module module = SurefireResults.read(reports);
        assertEquals(List.of(
                new Result("a.B", "ok", Outcome.PASSED),
                new Result("a.B", "bad", Outcome.FAILED),
                new Result("a.B", "broken", Outcome.FAILED),
                new Result("a.B", "later", Outcome.SKIPPED)), module.results());
        assertEquals(java.util.Set.of("a.B"), module.classesRun());
    }

    @Test
    void fallsBackToTheSuiteNameWhenTheTestcaseHasNoClassname() throws IOException {
        Files.writeString(reports.resolve("TEST-c.D.xml"), """
                <testsuite name="c.D"><testcase name="m"/></testsuite>
                """);
        assertEquals(List.of(new Result("c.D", "m", Outcome.PASSED)), SurefireResults.read(reports).results());
    }

    @Test
    void readAllCombinesSurefireAndFailsafeDirectoriesAndSkipsAMissingOne() throws IOException {
        Path surefire = Files.createDirectories(reports.resolve("surefire-reports"));
        Path failsafe = Files.createDirectories(reports.resolve("failsafe-reports"));
        Path absent = reports.resolve("no-such-reports");
        Files.writeString(surefire.resolve("TEST-a.Unit.xml"), """
                <testsuite name="a.Unit"><testcase name="u" classname="a.Unit"/></testsuite>
                """);
        Files.writeString(failsafe.resolve("TEST-a.It.xml"), """
                <testsuite name="a.It"><testcase name="i" classname="a.It"><skipped/></testcase></testsuite>
                """);
        Files.writeString(failsafe.resolve("failsafe-summary.xml"), "<not-a-test-report/>");
        SurefireResults.Module module = SurefireResults.readAll(java.util.List.of(surefire, failsafe, absent));
        assertEquals(List.of(
                new Result("a.Unit", "u", Outcome.PASSED),
                new Result("a.It", "i", Outcome.SKIPPED)), module.results());
        assertEquals(java.util.Set.of("a.Unit", "a.It"), module.classesRun());
    }

    @Test
    void refusesDocumentTypeDeclarations() throws IOException {
        Files.writeString(reports.resolve("TEST-x.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE testsuite [<!ENTITY e "boom">]>
                <testsuite name="x"><testcase name="&e;"/></testsuite>
                """);
        assertThrows(IOException.class, () -> SurefireResults.read(reports));
    }
}
