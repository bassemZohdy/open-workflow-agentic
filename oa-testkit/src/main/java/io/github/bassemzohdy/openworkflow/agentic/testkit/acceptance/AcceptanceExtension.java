package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Validates an {@link Acceptance} annotation before the test body runs.
 *
 * <p>Checks: the test id exists in requirements.json; each declared requirement exists and is verified by that
 * test; the test is not a parameterized template (its report name would not match the marker). Any failure fails
 * the test. On success the method is recorded as a marker for {@link AcceptanceStatus}.
 *
 * <p>The marker directory is {@code <basedir>/target/acceptance-markers}, where basedir is the module directory
 * surefire sets as the {@code basedir} system property. Override with {@value #MARKERS_PROPERTY}.
 */
public class AcceptanceExtension implements BeforeEachCallback {

    /** Optional override of the marker directory. */
    public static final String MARKERS_PROPERTY = "oa.acceptance.markers";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Optional<Acceptance> annotation = findAnnotation(context);
        if (annotation.isEmpty()) {
            return;
        }
        Acceptance a = annotation.get();
        if (context.getTestMethod().isPresent()
                && AnnotationSupport.isAnnotated(context.getRequiredTestMethod(), TestTemplate.class)) {
            throw new AssertionError("@Acceptance(\"" + a.value() + "\") is not supported on a parameterized test: "
                    + context.getRequiredTestMethod());
        }
        AcceptanceCatalog catalog = AcceptanceCatalog.current();
        validate(catalog, a);
        if (context.getTestMethod().isPresent()) {
            AcceptanceMarkers.record(markersDirectory(catalog.root()), new AcceptanceMarkers.Marker(
                    context.getRequiredTestClass().getName(), context.getRequiredTestMethod().getName(),
                    a.value(), List.of(a.requirements())));
        }
    }

    /** Validation shared with the tests; throws {@link AssertionError} when the annotation is wrong. */
    static void validate(AcceptanceCatalog catalog, Acceptance a) {
        if (!catalog.hasTest(a.value())) {
            throw new AssertionError("unknown acceptance test id '" + a.value() + "': not in "
                    + AcceptanceCatalog.REQUIREMENTS);
        }
        for (String req : a.requirements()) {
            if (!catalog.hasRequirement(req)) {
                throw new AssertionError("unknown requirement '" + req + "' on acceptance test " + a.value());
            }
            if (!catalog.verifies(a.value()).contains(req)) {
                throw new AssertionError("acceptance test " + a.value() + " does not verify requirement " + req
                        + " according to " + AcceptanceCatalog.REQUIREMENTS);
            }
        }
    }

    static Path markersDirectory(Path repoRoot) {
        String override = System.getProperty(MARKERS_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String basedir = System.getProperty("basedir");
        Path module = basedir != null ? Path.of(basedir) : repoRoot;
        return module.resolve("target").resolve(AcceptanceMarkers.DIRECTORY);
    }

    private static Optional<Acceptance> findAnnotation(ExtensionContext context) {
        Optional<Acceptance> onMethod = context.getTestMethod().map(m -> m.getAnnotation(Acceptance.class));
        if (onMethod.isPresent()) {
            return onMethod;
        }
        return context.getTestClass().map(c -> c.getAnnotation(Acceptance.class));
    }
}
