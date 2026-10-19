package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Markers that connect an annotated test method to its acceptance id.
 *
 * <p>Surefire 3.5.3 does not write JUnit report entries into its XML reports, so the extension records each
 * annotated method in {@code <module>/target/acceptance-markers/<class>.tsv} with one line per method:
 * {@code method TAB testId TAB requirement,requirement}. The status generator joins a marker with the surefire
 * testcase of the same class and method in the same module.
 */
public final class AcceptanceMarkers {

    /** One annotated test method as recorded by the extension. */
    public record Marker(String className, String method, String testId, List<String> requirements) {
    }

    public static final String DIRECTORY = "acceptance-markers";

    private AcceptanceMarkers() {
    }

    /** Records (or replaces) the marker of one method in the given markers directory. */
    public static void record(Path markersDir, Marker marker) throws IOException {
        Files.createDirectories(markersDir);
        Path file = markersDir.resolve(marker.className() + ".tsv");
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.split("\t", -1)[0].equals(marker.method())) {
                    lines.add(line);
                }
            }
        }
        lines.add(marker.method() + "\t" + marker.testId() + "\t" + String.join(",", marker.requirements()));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Reads all markers under a markers directory; a missing directory yields no markers. */
    public static List<Marker> read(Path markersDir) throws IOException {
        List<Marker> out = new ArrayList<>();
        if (!Files.isDirectory(markersDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(markersDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".tsv")).sorted().toList()) {
                String className = file.getFileName().toString().replaceAll("\\.tsv$", "");
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 3) {
                        throw new IOException("malformed marker line in " + file + ": " + line);
                    }
                    List<String> reqs = parts[2].isEmpty() ? List.of() : List.of(parts[2].split(","));
                    out.add(new Marker(className, parts[0], parts[1], reqs));
                }
            }
        }
        return out;
    }
}
