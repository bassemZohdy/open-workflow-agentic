package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcceptanceMarkersTest {

    @TempDir
    Path dir;

    @Test
    void recordsAndReadsBack() throws IOException {
        AcceptanceMarkers.record(dir, new AcceptanceMarkers.Marker("a.B", "one", "T01", List.of("M01", "M02")));
        AcceptanceMarkers.record(dir, new AcceptanceMarkers.Marker("a.B", "two", "T02", List.of()));
        assertEquals(List.of(
                new AcceptanceMarkers.Marker("a.B", "one", "T01", List.of("M01", "M02")),
                new AcceptanceMarkers.Marker("a.B", "two", "T02", List.of())),
                AcceptanceMarkers.read(dir));
    }

    @Test
    void reRecordingAMethodReplacesItsLine() throws IOException {
        AcceptanceMarkers.record(dir, new AcceptanceMarkers.Marker("a.B", "one", "T01", List.of()));
        AcceptanceMarkers.record(dir, new AcceptanceMarkers.Marker("a.B", "one", "T09", List.of()));
        assertEquals(List.of(new AcceptanceMarkers.Marker("a.B", "one", "T09", List.of())), AcceptanceMarkers.read(dir));
    }

    @Test
    void missingDirectoryHasNoMarkers() throws IOException {
        assertEquals(List.of(), AcceptanceMarkers.read(dir.resolve("absent")));
    }

    @Test
    void malformedLineIsAnError() throws IOException {
        Files.writeString(dir.resolve("a.B.tsv"), "only-one-column\n");
        assertThrows(IOException.class, () -> AcceptanceMarkers.read(dir));
    }
}
