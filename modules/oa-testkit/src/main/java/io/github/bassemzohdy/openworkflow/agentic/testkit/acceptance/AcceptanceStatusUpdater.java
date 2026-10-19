package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Pure decision logic of the status generator: from the recorded statuses and the observed test outcomes,
 * decide what changes and whether a previously automated test has regressed.
 *
 * <p>Rules:
 * <ul>
 *   <li>A test is passing when every observation of it passed (at least one observation exists).</li>
 *   <li>A passing test that is not {@code automated} is promoted to {@code automated}.</li>
 *   <li>A test recorded as {@code automated} that is not passing (failed, skipped or missing) is a
 *       <b>regression</b>. The status is never lowered automatically.</li>
 *   <li>Any other status is left alone: the generator never writes {@code planned}, {@code partial} or
 *       {@code not-started}.</li>
 * </ul>
 */
public final class AcceptanceStatusUpdater {

    /** One testcase (or a missing one) that carries an acceptance id. */
    public record Observation(String testId, String module, String className, String method, Verdict verdict) {
    }

    public enum Verdict { PASSED, FAILED, SKIPPED, MISSING }

    /** The result of {@link #plan}: the new statuses, the changes and the regressions. */
    public record Plan(Map<String, AcceptanceCatalog.Entry> statuses, List<String> changes, List<String> regressions) {
        public boolean hasRegressions() {
            return !regressions.isEmpty();
        }

        /** True when the recorded file differs from what the build proves. */
        public boolean hasPendingChanges() {
            return !changes.isEmpty();
        }
    }

    private AcceptanceStatusUpdater() {
    }

    public static Plan plan(AcceptanceCatalog catalog, List<Observation> observations) {
        Map<String, List<Observation>> byTest = new TreeMap<>();
        for (Observation o : observations) {
            byTest.computeIfAbsent(o.testId(), k -> new ArrayList<>()).add(o);
        }
        Map<String, AcceptanceCatalog.Entry> next = new LinkedHashMap<>(catalog.recordedStatuses());
        List<String> changes = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        for (Map.Entry<String, List<Observation>> e : byTest.entrySet()) {
            String id = e.getKey();
            List<Observation> obs = e.getValue();
            AcceptanceCatalog.Entry recorded = catalog.status(id);
            boolean passing = obs.stream().allMatch(o -> o.verdict() == Verdict.PASSED);
            if (passing) {
                String where = obs.stream().map(o -> o.module() + ":" + simple(o.className()) + "#" + o.method())
                        .distinct().collect(Collectors.joining("; "));
                AcceptanceCatalog.Entry updated = new AcceptanceCatalog.Entry("automated", where);
                if (!updated.equals(recorded)) {
                    next.put(id, updated);
                    String what = recorded.status().equals("automated")
                            ? "where updated" : recorded.status() + " -> automated";
                    changes.add(id + ": " + what + " (" + where + ")");
                }
            } else if ("automated".equals(recorded.status())) {
                String why = obs.stream().filter(o -> o.verdict() != Verdict.PASSED)
                        .map(o -> o.module() + ":" + simple(o.className()) + "#" + o.method() + " " + o.verdict())
                        .collect(Collectors.joining(", "));
                regressions.add(id + " is recorded as automated but is not passing: " + why);
            }
        }
        return new Plan(next, changes, regressions);
    }

    /** Tests recorded as automated that have no observation at all are missing and count as regressions. */
    public static List<String> missingAutomated(AcceptanceCatalog catalog, List<Observation> observations) {
        var seen = observations.stream().map(Observation::testId).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, AcceptanceCatalog.Entry> e : catalog.recordedStatuses().entrySet()) {
            if ("automated".equals(e.getValue().status()) && !seen.contains(e.getKey())) {
                out.add(e.getKey() + " is recorded as automated but no annotated test reported it");
            }
        }
        return out;
    }

    static String simple(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
