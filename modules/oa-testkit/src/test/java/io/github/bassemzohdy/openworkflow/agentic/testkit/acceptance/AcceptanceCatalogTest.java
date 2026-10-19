package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcceptanceCatalogTest {

    @TempDir
    Path tmp;

    @Test
    void findsTheRepositoryRootAbovePlainStartDirectories() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(root.resolve("docs/requirements"));
        Files.writeString(root.resolve("docs/requirements/requirements.json"), "{}");
        Path deep = Files.createDirectories(root.resolve("a/b/c"));
        assertEquals(root, AcceptanceCatalog.findRepoRoot(deep));
    }

    @Test
    void failsWhenNoRepositoryIsAbove() {
        Path lonely = tmp.resolve("nothing/here");
        assertThrows(IllegalStateException.class, () -> AcceptanceCatalog.findRepoRoot(lonely.toAbsolutePath()));
    }

    @Test
    void loadsTheRealRepositoryRecords() throws IOException {
        AcceptanceCatalog catalog = AcceptanceCatalog.current();
        assertEquals(39, catalog.testIds().size());
        assertTrue(catalog.testIds().stream().allMatch(id -> id.matches("T\\d\\d")), catalog.testIds().toString());
        assertEquals(Set.of("M01", "M02", "M12", "M14"), catalog.verifies("T01"));
        assertTrue(catalog.hasRequirement("M03"));
        assertFalse(catalog.hasRequirement("M99"));
        assertEquals("planned", catalog.status("T12").status());
        assertEquals("not-started", catalog.status("T40").status());
    }

    @Test
    void rejectsAStatusOutsideTheDocumentedVocabulary() throws IOException {
        Path root = fixtureRepo("{\"T01\": {\"status\": \"done\", \"where\": \"\"}}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AcceptanceCatalog.load(root));
        assertTrue(e.getMessage().contains("'done'"), e.getMessage());
    }

    @Test
    void rejectsAStatusForAnUnknownTest() throws IOException {
        Path root = fixtureRepo("{\"T77\": {\"status\": \"planned\", \"where\": \"\"}}");
        assertThrows(IllegalStateException.class, () -> AcceptanceCatalog.load(root));
    }

    @Test
    void rejectsDuplicateTestIds() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("dup"));
        Files.createDirectories(root.resolve("docs/requirements"));
        Files.writeString(root.resolve("docs/requirements/requirements.json"), """
                {"mandatory": [], "high": [], "optional": [], "scenarios": [
                  {"id": "S1", "tests": [{"id": "T01", "verifies": []}, {"id": "T01", "verifies": []}]}]}
                """);
        Files.writeString(root.resolve("docs/requirements/test-status.json"), "{}");
        assertThrows(IllegalStateException.class, () -> AcceptanceCatalog.load(root));
    }

    /** A fixture repository with one test (T01) and the given test-status.json content. */
    private Path fixtureRepo(String status) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("fixture-" + Math.abs(status.hashCode())));
        Files.createDirectories(root.resolve("docs/requirements"));
        Files.writeString(root.resolve("docs/requirements/requirements.json"), """
                {"mandatory": [{"id": "M01"}], "high": [], "optional": [], "scenarios": [
                  {"id": "S1", "tests": [{"id": "T01", "verifies": ["M01"]}]}]}
                """);
        Files.writeString(root.resolve("docs/requirements/test-status.json"), status);
        return root;
    }
}
