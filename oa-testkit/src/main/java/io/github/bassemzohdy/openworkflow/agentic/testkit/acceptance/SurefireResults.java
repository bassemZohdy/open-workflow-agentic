package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the testcase outcomes from a module's JUnit XML reports: {@code target/surefire-reports} (unit tests) and
 * {@code target/failsafe-reports} (integration tests), both {@code TEST-*.xml}. Both use the same format.
 */
public final class SurefireResults {

    /** The report directories, relative to a module's {@code target} directory: unit tests, then integration tests. */
    public static final List<String> REPORT_DIRECTORIES = List.of("surefire-reports", "failsafe-reports");

    public enum Outcome { PASSED, FAILED, SKIPPED }

    /** One testcase. {@code className} and {@code name} are how surefire writes them (the method name for JUnit 5). */
    public record Result(String className, String name, Outcome outcome) {
    }

    /** The results of one module, and the classes that ran (so missing markers can be told apart). */
    public record Module(List<Result> results, Set<String> classesRun) {
        public List<Result> of(String className, String method) {
            return results.stream().filter(r -> r.className().equals(className) && r.name().equals(method)).toList();
        }
    }

    private SurefireResults() {
    }

    /** Reads one report directory. */
    public static Module read(Path reportsDir) throws IOException {
        return readAll(List.of(reportsDir));
    }

    /**
     * Reads several report directories as one module (for example surefire and failsafe). A directory that does not
     * exist contributes nothing. A testcase that appears in more than one directory is kept once per appearance; the
     * caller decides how to combine them.
     */
    public static Module readAll(List<Path> reportsDirs) throws IOException {
        List<Result> results = new ArrayList<>();
        Set<String> classes = new TreeSet<>();
        for (Path reportsDir : reportsDirs) {
            if (!Files.isDirectory(reportsDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(reportsDir)) {
                for (Path file : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                    Element suite = parse(file).getDocumentElement();
                    String suiteName = suite.getAttribute("name");
                    classes.add(suiteName);
                    for (Element tc : children(suite, "testcase")) {
                        String cls = tc.hasAttribute("classname") ? tc.getAttribute("classname") : suiteName;
                        results.add(new Result(cls, tc.getAttribute("name"), outcomeOf(tc)));
                    }
                }
            }
        }
        return new Module(results, classes);
    }

    private static Outcome outcomeOf(Element testcase) {
        if (!children(testcase, "failure").isEmpty() || !children(testcase, "error").isEmpty()) {
            return Outcome.FAILED;
        }
        if (!children(testcase, "skipped").isEmpty()) {
            return Outcome.SKIPPED;
        }
        return Outcome.PASSED;
    }

    private static Document parse(Path file) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("cannot read surefire report " + file + ": " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && e.getTagName().equals(name)) {
                out.add(e);
            }
        }
        return out;
    }
}
