package io.github.bassemzohdy.openworkflow.agentic.policy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.Path;
import java.io.File;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * WP-103 default deny on HTTP: every JAX-RS route in the product is found by scanning the classpath, not listed by hand,
 * so a new resource that forgets authentication fails this test the day it is added. An anonymous request (no token, no
 * development header) must be answered 401, never 2xx, 403 or 404, and never reach business logic.
 *
 * <p>Routes that are public on purpose are named in {@link #PUBLIC}; each entry needs a reason.
 */
@QuarkusTest
class HttpRouteDefaultDenyTest {

    /**
     * Anonymous on purpose: path prefix -> why. {@code /dev/lifecycle} is the process-test probe of WP-105c: it answers 404
     * unless {@code agentic.dev.lifecycle-probes=true}, which is false by default and set only in the {@code %test}
     * profile (application.properties); it must never be set in a deployment.
     */
    private static final java.util.Map<String, String> PUBLIC = java.util.Map.of(
            "/dev/lifecycle", "process-test probe, 404 unless agentic.dev.lifecycle-probes=true (%test only)");

    /**
     * KNOWN GAPS (WP-103): routes that are reachable without a verified caller. Empty since {@code POST /agents/research}
     * was authenticated. An entry is probed with a malformed body, may answer anything except 401 and 2xx, and fails
     * the test the day it answers 401 (remove it then).
     */
    private static final Set<String> KNOWN_GAPS = Set.of();

    /** Product classes that could not be loaded for inspection; the test fails if there is any. */
    private static final List<String> UNLOADABLE = new ArrayList<>();

    private static final String ROOT = "io/github/bassemzohdy/openworkflow/agentic";

    /** A public exemption covers its own path and the segments below it, never a longer name that merely starts with it. */
    private static boolean isPublic(String path) {
        return PUBLIC.keySet().stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    @Test
    void publicExemptionsAreBoundToWholePathSegments() {
        assertTrue(isPublic("/dev/lifecycle"));
        assertTrue(isPublic("/dev/lifecycle/effect"));
        assertFalse(isPublic("/dev/lifecycle-evil"));
        assertFalse(isPublic("/dev/lifecycles"));
        assertFalse(isPublic("/dev"));
    }

    @Test
    void everyDiscoveredRouteRefusesAnAnonymousCallerWith401() throws Exception {
        List<String> routes = routes();
        assertEquals(List.of(), UNLOADABLE, "classes that could not be inspected for routes");
        assertTrue(routes.size() >= 10, () -> "route discovery found too few routes: " + routes);
        List<String> failures = new ArrayList<>();
        for (String route : routes) {
            String[] parts = route.split(" ", 3);
            String path = parts[1];
            String contentType = parts[2];
            if (isPublic(path)) {
                continue;
            }
            route = parts[0] + " " + path; // the report names the route, not the probe media type
            boolean gap = KNOWN_GAPS.contains(parts[0] + " " + path);
            // A known gap is reachable, so a valid-looking body would RUN its logic (the research agent writes SDK
            // checkpoints; a failing run poisons the shared persistence gate for unrelated tests). The probe is therefore
            // malformed JSON: a route that is still open rejects it as a 400 before any logic runs, and a route that has
            // been authenticated answers 401.
            int status = given().redirects().follow(false).contentType(contentType).body(gap ? "{" : "{}")
                    .when().request(parts[0], path).statusCode();
            if (gap) {
                if (status == 401) {
                    failures.add(route + " is now authenticated: remove it from KNOWN_GAPS");
                } else if (status / 100 == 2) {
                    failures.add(route + " (known gap) answered " + status + " to a malformed body");
                }
                continue;
            }
            if (status != 401) {
                failures.add(route + " -> " + status);
            }
        }
        assertEquals(List.of(), failures, "routes that answer an anonymous caller with anything but 401");
        assertFalse(routes.isEmpty());
    }

    /** "VERB /path" for every resource method, path parameters replaced by a plausible value. */
    private static List<String> routes() throws Exception {
        Set<String> found = new TreeSet<>();
        for (Class<?> type : classes()) {
            Path base = type.getAnnotation(Path.class);
            if (base == null) {
                continue;
            }
            for (Method m : type.getMethods()) { // public, including inherited
                String verb = verb(m);
                if (verb == null) {
                    continue;
                }
                Path sub = m.getAnnotation(Path.class);
                String path = "/" + trim(base.value()) + (sub == null ? "" : "/" + trim(sub.value()));
                found.add(verb + " " + path.replaceAll("/+", "/").replaceAll("\\{[^}/]+\\}", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
                        + " " + consumes(type, m));
            }
        }
        return new ArrayList<>(found);
    }

    /** The media type the route consumes: the method's {@code @Consumes}, else the class's, else JSON. */
    private static String consumes(Class<?> type, Method m) {
        jakarta.ws.rs.Consumes declared = m.getAnnotation(jakarta.ws.rs.Consumes.class);
        if (declared == null) {
            declared = type.getAnnotation(jakarta.ws.rs.Consumes.class);
        }
        if (declared == null || declared.value().length == 0 || declared.value()[0].contains("*")) {
            return "application/json";
        }
        return declared.value()[0];
    }

    private static String trim(String s) {
        return s.replaceAll("^/+|/+$", "");
    }

    /** The HTTP method of a resource method: any annotation meta-annotated with {@code @HttpMethod} (custom ones too). */
    private static String verb(Method m) {
        for (java.lang.annotation.Annotation a : m.getAnnotations()) {
            jakarta.ws.rs.HttpMethod http = a.annotationType().getAnnotation(jakarta.ws.rs.HttpMethod.class);
            if (http != null) {
                return http.value();
            }
        }
        return null;
    }

    private static List<Class<?>> classes() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<String> names = new ArrayList<>();
        Enumeration<URL> roots = loader.getResources(ROOT);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if ("jar".equals(url.getProtocol())) {
                try (var jar = ((JarURLConnection) url.openConnection()).getJarFile()) {
                    jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class") && n.startsWith(ROOT))
                            .forEach(names::add);
                }
            } else {
                File dir = new File(url.toURI());
                String prefix = dir.getAbsolutePath();
                try (var walk = java.nio.file.Files.walk(dir.toPath())) {
                    walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> names.add(
                            ROOT + p.toAbsolutePath().toString().substring(prefix.length()).replace(File.separatorChar, '/')));
                }
            }
        }
        List<Class<?>> out = new ArrayList<>();
        for (String name : names) {
            String cn = name.substring(0, name.length() - 6).replace('/', '.');
            if (cn.contains("$") && !cn.matches(".*\\$[A-Za-z].*")) {
                continue;
            }
            try {
                Class<?> c = Class.forName(cn, false, loader);
                java.security.CodeSource source = c.getProtectionDomain().getCodeSource();
                boolean testOnly = source != null && source.getLocation() != null
                        && source.getLocation().getPath().contains("test-classes");
                if (!testOnly && c.isAnnotationPresent(Path.class)) {
                    out.add(c);
                }
            } catch (Throwable unloadable) {
                // a class that cannot be inspected may hide a route: it must be visible, never silently skipped
                UNLOADABLE.add(cn + " (" + unloadable.getClass().getSimpleName() + ")");
            }
        }
        return out;
    }
}
