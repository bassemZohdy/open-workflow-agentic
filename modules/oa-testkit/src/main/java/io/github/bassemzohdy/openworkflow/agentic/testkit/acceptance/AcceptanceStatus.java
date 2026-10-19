package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatusUpdater.Observation;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatusUpdater.Plan;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatusUpdater.Verdict;
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.SurefireResults.Outcome;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Generates docs/requirements/test-status.json from the surefire and failsafe reports of a build.
 *
 * <pre>
 * mvn -B verify                                   # produces the reports (run the full build, not a -Dtest subset)
 * java -cp "modules/oa-testkit/target/classes:$(cat modules/oa-testkit/target/classpath.txt)" \
 *     io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatus [--root DIR] [--check]
 * </pre>
 *
 * <p>Without {@code --check} the file is updated when the build proves a new automated test. With {@code --check}
 * nothing is written and the exit status is non-zero when the file is out of date or when an automated test has
 * regressed. Exit status: 0 consistent, 1 regression or pending change, 2 usage or input error.
 */
public final class AcceptanceStatus {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private static final List<String> PRUNED_DIRECTORIES = List.of(".git", ".claude", "node_modules");

    private AcceptanceStatus() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path rootArg = null;
        boolean check = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> check = true;
                case "--root" -> {
                    if (i + 1 >= args.length) {
                        err.println("--root needs a directory");
                        return USAGE;
                    }
                    rootArg = Path.of(args[++i]);
                }
                default -> {
                    err.println("unknown argument: " + args[i]);
                    err.println("usage: AcceptanceStatus [--root DIR] [--check]");
                    return USAGE;
                }
            }
        }
        try {
            AcceptanceCatalog catalog = rootArg == null
                    ? AcceptanceCatalog.current() : AcceptanceCatalog.load(rootArg.toAbsolutePath().normalize());
            return generate(catalog, check, out, err);
        } catch (IOException | IllegalStateException e) {
            err.println("cannot read acceptance records: " + e.getMessage());
            return USAGE;
        }
    }

    private static int generate(AcceptanceCatalog catalog, boolean check, PrintStream out, PrintStream err)
            throws IOException {
        List<Path> modules = findModules(catalog.root());
        if (modules.isEmpty()) {
            err.println("no surefire or failsafe reports under " + catalog.root() + "; run mvn -B verify first");
            return USAGE;
        }
        List<Observation> observations = new ArrayList<>();
        int noReportEntry = 0;
        for (Path module : modules) {
            String name = catalog.root().relativize(module).toString().replace('\\', '/');
            name = name.isEmpty() ? "." : name;
            SurefireResults.Module results = SurefireResults.readAll(reportDirectories(module));
            for (AcceptanceMarkers.Marker m : AcceptanceMarkers.read(module.resolve("target/" + AcceptanceMarkers.DIRECTORY))) {
                if (!catalog.hasTest(m.testId())) {
                    err.println("marker for unknown acceptance test " + m.testId() + " in " + name + ": " + m.className());
                    return FAILED;
                }
                // A marker whose class has no entry in any report means the class did not run (or was skipped as a
                // whole). For an automated test that is a missing test, so it is observed as MISSING.
                Verdict verdict;
                if (results.classesRun().contains(m.className())) {
                    verdict = verdictOf(results.of(m.className(), m.method()));
                } else {
                    noReportEntry++;
                    verdict = Verdict.MISSING;
                }
                observations.add(new Observation(m.testId(), name, m.className(), m.method(), verdict));
            }
        }
        Plan plan = AcceptanceStatusUpdater.plan(catalog, observations);
        List<String> regressions = new ArrayList<>(plan.regressions());
        regressions.addAll(AcceptanceStatusUpdater.missingAutomated(catalog, observations));

        int passing = (int) observations.stream().filter(o -> o.verdict() == Verdict.PASSED).count();
        out.println("annotated test methods observed: " + observations.size() + " (passing: " + passing
                + "); markers whose class has no entry in any surefire or failsafe report: " + noReportEntry);
        plan.changes().forEach(c -> out.println("change: " + c));
        regressions.forEach(r -> err.println("regression: " + r));

        if (!regressions.isEmpty()) {
            err.println("test-status.json was not written because automated tests regressed");
            return FAILED;
        }
        if (check) {
            if (plan.hasPendingChanges()) {
                err.println("docs/requirements/test-status.json is out of date; run the generator without --check");
                return FAILED;
            }
            out.println("test-status.json is consistent with the build");
            return OK;
        }
        if (plan.hasPendingChanges()) {
            Files.writeString(catalog.statusFile(), render(plan.statuses()), StandardCharsets.UTF_8);
            out.println("wrote " + catalog.statusFile());
        } else {
            out.println("test-status.json is already up to date");
        }
        return OK;
    }

    /** Worst outcome of the testcases of one method: failed beats skipped beats passed; no testcase is missing. */
    static Verdict verdictOf(List<SurefireResults.Result> testcases) {
        if (testcases.isEmpty()) {
            return Verdict.MISSING;
        }
        if (testcases.stream().anyMatch(t -> t.outcome() == Outcome.FAILED)) {
            return Verdict.FAILED;
        }
        if (testcases.stream().anyMatch(t -> t.outcome() == Outcome.SKIPPED)) {
            return Verdict.SKIPPED;
        }
        return Verdict.PASSED;
    }

    /** The report directories of one module (surefire and failsafe), whether or not they exist. */
    static List<Path> reportDirectories(Path module) {
        return SurefireResults.REPORT_DIRECTORIES.stream().map(d -> module.resolve("target").resolve(d)).toList();
    }

    /**
     * Module directories: the parent of a target/surefire-reports or target/failsafe-reports directory, searched to
     * depth 4, covering both flat modules and apps/<module> or modules/<module>. A module that has both
     * report directories is listed once.
     */
    static List<Path> findModules(Path root) throws IOException {
        Set<Path> out = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root, 4)) {
            for (Path p : walk.filter(Files::isDirectory)
                    .filter(p -> SurefireResults.REPORT_DIRECTORIES.contains(p.getFileName().toString()))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("target"))
                    .filter(p -> !pruned(root, p)).toList()) {
                out.add(p.getParent().getParent());
            }
        }
        return List.copyOf(out);
    }

    private static boolean pruned(Path root, Path p) {
        for (Path part : root.relativize(p)) {
            if (PRUNED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** Renders the statuses in the layout of the committed file: sorted ids, two-space indent. */
    static String render(Map<String, AcceptanceCatalog.Entry> statuses) throws IOException {
        ObjectMapper json = new ObjectMapper();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, AcceptanceCatalog.Entry> e : new TreeMap<>(statuses).entrySet()) {
            sb.append(first ? "\n" : ",\n").append("  ").append(json.writeValueAsString(e.getKey())).append(": {\n")
                    .append("    \"status\": ").append(json.writeValueAsString(e.getValue().status())).append(",\n")
                    .append("    \"where\": ").append(json.writeValueAsString(e.getValue().where())).append("\n  }");
            first = false;
        }
        return sb.append(first ? "}\n" : "\n}\n").toString();
    }
}
