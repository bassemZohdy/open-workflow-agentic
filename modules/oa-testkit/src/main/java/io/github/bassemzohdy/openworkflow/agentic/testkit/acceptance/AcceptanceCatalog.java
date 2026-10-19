package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The acceptance tests and requirements as recorded in {@code docs/requirements}: the valid test ids with the
 * requirements each verifies, and the recorded status of each test.
 *
 * <p>Test ids come from {@code requirements.json} because {@code test-status.json} only lists tests whose status
 * is not the default {@code not-started}.
 */
public final class AcceptanceCatalog {

    /** The status values allowed in test-status.json (the vocabulary of docs/test-cases.md). */
    public static final Set<String> STATUSES = Set.of("not-started", "planned", "partial", "automated");

    static final String REQUIREMENTS = "docs/requirements/requirements.json";
    static final String STATUS = "docs/requirements/test-status.json";
    /** Optional override of the repository root, for tests that use a fixture repository. */
    public static final String ROOT_PROPERTY = "oa.repo.root";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A recorded status: {@code status} is one of {@link #STATUSES}, {@code where} says where it is automated. */
    public record Entry(String status, String where) {
    }

    private final Path root;
    private final Map<String, Set<String>> verifies;
    private final Set<String> requirementIds;
    private final Map<String, Entry> statuses;

    private AcceptanceCatalog(Path root, Map<String, Set<String>> verifies, Set<String> requirementIds,
                              Map<String, Entry> statuses) {
        this.root = root;
        this.verifies = verifies;
        this.requirementIds = requirementIds;
        this.statuses = statuses;
    }

    /**
     * Finds the repository root: the {@code oa.repo.root} system property if set, otherwise the nearest directory
     * at or above {@code start} that contains docs/requirements/requirements.json.
     */
    public static Path findRepoRoot(Path start) {
        String override = System.getProperty(ROOT_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve(REQUIREMENTS))) {
                return p;
            }
        }
        throw new IllegalStateException("no " + REQUIREMENTS + " found at or above " + start);
    }

    /** Loads the catalog of the repository that contains the current working directory. */
    public static AcceptanceCatalog current() throws IOException {
        return load(findRepoRoot(Path.of("")));
    }

    public static AcceptanceCatalog load(Path root) throws IOException {
        Map<String, Set<String>> verifies = new TreeMap<>();
        Set<String> requirementIds = new LinkedHashSet<>();
        JsonNode req = JSON.readTree(root.resolve(REQUIREMENTS).toFile());
        for (String level : List.of("mandatory", "high", "optional")) {
            for (JsonNode r : req.path(level)) {
                requirementIds.add(r.path("id").asText());
            }
        }
        for (JsonNode scenario : req.path("scenarios")) {
            for (JsonNode test : scenario.path("tests")) {
                Set<String> ids = new LinkedHashSet<>();
                test.path("verifies").forEach(v -> ids.add(v.asText()));
                if (verifies.put(test.path("id").asText(), ids) != null) {
                    throw new IllegalStateException("duplicate test id in " + REQUIREMENTS + ": " + test.path("id").asText());
                }
            }
        }
        Map<String, Entry> statuses = new TreeMap<>();
        JsonNode st = JSON.readTree(root.resolve(STATUS).toFile());
        st.fields().forEachRemaining(f -> {
            String status = f.getValue().path("status").asText();
            if (!STATUSES.contains(status)) {
                throw new IllegalStateException("test " + f.getKey() + " has status '" + status
                        + "'; allowed values are " + STATUSES);
            }
            statuses.put(f.getKey(), new Entry(status, f.getValue().path("where").asText("")));
        });
        for (String id : statuses.keySet()) {
            if (!verifies.containsKey(id)) {
                throw new IllegalStateException("test-status.json names unknown test " + id);
            }
        }
        return new AcceptanceCatalog(root, Collections.unmodifiableMap(verifies),
                Collections.unmodifiableSet(requirementIds), statuses);
    }

    public Path root() {
        return root;
    }

    public boolean hasTest(String id) {
        return verifies.containsKey(id);
    }

    public boolean hasRequirement(String id) {
        return requirementIds.contains(id);
    }

    /** The requirement ids a test verifies, or an empty set for an unknown test. */
    public Set<String> verifies(String id) {
        return verifies.getOrDefault(id, Set.of());
    }

    public Set<String> testIds() {
        return verifies.keySet();
    }

    /** The recorded status; tests not listed in test-status.json are {@code not-started}. */
    public Entry status(String id) {
        return statuses.getOrDefault(id, new Entry("not-started", ""));
    }

    /** The recorded statuses (only tests that are not not-started). */
    public Map<String, Entry> recordedStatuses() {
        return Collections.unmodifiableMap(statuses);
    }

    public Path statusFile() {
        return root.resolve(STATUS);
    }
}
