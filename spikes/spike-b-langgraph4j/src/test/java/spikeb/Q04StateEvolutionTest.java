package spikeb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spikeb.support.ChildProcess;

/**
 * Q4: can a checkpoint written by one version of the state and graph classes be resumed by another version?
 * Each variant is compiled from src/test/evolve/<variant>. The checkpoint is written by the v1 classes in one JVM
 * (interrupted before "work") and resumed by the variant classes in a second JVM.
 */
class Q04StateEvolutionTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @BeforeAll
    static void resetSchema() throws Exception {
        Db.resetSchema("q04");
    }

    private static Path compile(String variant) throws IOException {
        Path src = Path.of("src/test/evolve", variant, "spikeb/evolve/EvolveGraph.java").toAbsolutePath();
        Path out = Path.of("target/evolve", variant).toAbsolutePath();
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-proc:none", "-d", out.toString(),
                "-cp", System.getProperty("java.class.path"), src.toString());
        assertEquals(0, rc, "variant " + variant + " must compile");
        return out;
    }

    /** Returns the single RESULT or ERROR line of the resume JVM. */
    static String runVariant(String variant) throws Exception {
        Path v1 = compile("v1");
        Path resumeClasses = "v1".equals(variant) ? v1 : compile(variant);
        String thread = "q04-" + variant + "-" + System.nanoTime();

        String v1Cp = v1 + File.pathSeparator + System.getProperty("java.class.path");
        List<String> written = ChildProcess.start(v1Cp, "spikeb.evolve.EvolveGraph", "write", "q04", thread)
                .finish(WAIT);
        assertTrue(ChildProcess.linesWith(written, "WRITTEN next=work").size() == 1,
                "v1 writes a checkpoint interrupted before work: " + written);

        String resumeCp = resumeClasses + File.pathSeparator + System.getProperty("java.class.path");
        List<String> out = ChildProcess.start(resumeCp, "spikeb.evolve.EvolveGraph", "resume", "q04", thread)
                .finish(WAIT);
        List<String> result = out.stream().filter(l -> l.startsWith("RESULT ") || l.startsWith("ERROR ")).toList();
        assertEquals(1, result.size(), "one outcome line expected: " + out);
        System.out.println("Q04 " + variant + " -> " + result.get(0));
        return result.get(0);
    }

    @Test
    void Q04_control_sameVersionResumes() throws Exception {
        assertEquals("RESULT summary=work on evidence-1", runVariant("v1"));
    }

    @Test
    void Q04_addStateKey_resumes_newKeyDefaults() throws Exception {
        assertEquals("RESULT summary=work on evidence-1 severity=low", runVariant("addStateKey"));
    }

    @Test
    void Q04_renameStateKey_resumesButNodeFailsOnMissingKey() throws Exception {
        assertEquals("ERROR java.lang.IllegalStateException: missing finding", runVariant("renameStateKey"));
    }

    @Test
    void Q04_renameStateKeyWithFallback_resumesFromOldKey() throws Exception {
        assertEquals("RESULT summary=work on evidence-1", runVariant("renameStateKeyFallback"));
    }

    @Test
    void Q04_addRecordFieldWithPinnedSerialVersionUID_resumesWithNullField() throws Exception {
        assertEquals("RESULT summary=work on evidence-1 from null", runVariant("addPojoFieldPinned"));
    }

    @Test
    void Q04_removeRecordFieldWithPinnedSerialVersionUID_resumesAndDropsField() throws Exception {
        assertEquals("RESULT summary=work on evidence-1", runVariant("removePojoFieldPinned"));
    }

    @Test
    void Q04_addRecordFieldWithoutSerialVersionUID_failsToDeserialize() throws Exception {
        String outcome = runVariant("addPojoFieldUnpinned");
        assertTrue(outcome.startsWith("ERROR java.io.InvalidClassException: spikeb.evolve.EvolveGraph$Finding; "
                + "local class incompatible: stream classdesc serialVersionUID = 1, local class serialVersionUID = "),
                "the implicit serialVersionUID changes with the class shape: " + outcome);
    }

    @Test
    void Q04_renameValueClass_failsWithClassNotFound() throws Exception {
        assertEquals("ERROR java.lang.ClassNotFoundException: spikeb.evolve.EvolveGraph$Finding",
                runVariant("renameClass"));
    }
}
