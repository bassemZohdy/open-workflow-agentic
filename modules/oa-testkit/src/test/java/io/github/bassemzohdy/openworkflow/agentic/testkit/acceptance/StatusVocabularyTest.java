package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The generator must not invent statuses. The vocabulary is written in tools/render_requirements.py, which renders
 * docs/test-cases.md; this test reads it from there and compares it with {@link AcceptanceCatalog#STATUSES}.
 */
class StatusVocabularyTest {

    private static final Pattern VOCABULARY = Pattern.compile("Status values: ((?:`[a-z-]+`(?:, )?)+)\\.");

    @Test
    void generatorVocabularyMatchesTheRenderedDocumentation() throws IOException {
        Path root = AcceptanceCatalog.findRepoRoot(Path.of(""));
        String script = Files.readString(root.resolve("tools/render_requirements.py"));
        Matcher m = VOCABULARY.matcher(script);
        assertTrue(m.find(), "no 'Status values:' line in tools/render_requirements.py");
        Set<String> documented = new TreeSet<>(Arrays.stream(m.group(1).split(", "))
                .map(s -> s.replace("`", "")).toList());
        assertEquals(new TreeSet<>(AcceptanceCatalog.STATUSES), documented);
    }
}
