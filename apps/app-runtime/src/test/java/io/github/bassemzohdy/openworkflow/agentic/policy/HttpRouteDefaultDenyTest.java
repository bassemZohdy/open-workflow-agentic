package io.github.bassemzohdy.openworkflow.agentic.policy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
     * KNOWN GAP (WP-103, open): routes that are reachable without a verified caller today. Each entry asserts the
     * current behaviour so that the gap is visible and so that fixing it fails this test until the entry is removed.
     * {@code POST /agents/research} runs the research agent for an anonymous caller. Authenticating it needs a credential
     * for the workflow's {@code call: http} step, which is a design change in shared API code
     * (docs/handoffs/claude/WP-103.md, request H1).
     */
    private static final Set<String> KNOWN_GAPS = Set.of("POST /agents/research");

    private static final String ROOT = "io/github/bassemzohdy/openworkflow/agentic";

    @Test
    void everyDiscoveredRouteRefusesAnAnonymousCallerWith401() throws Exception {
        List<String> routes = routes();
        assertTrue(routes.size() >= 10, () -> "route discovery found too few routes: " + routes);
        List<String> failures = new ArrayList<>();
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            String path = parts[1];
            if (PUBLIC.keySet().stream().anyMatch(path::startsWith)) {
                continue;
            }
            int status = given().redirects().follow(false).contentType("application/json").body("{}")
                    .when().request(parts[0], path).statusCode();
            if (KNOWN_GAPS.contains(route)) {
                if (status == 401) {
                    failures.add(route + " is now authenticated: remove it from KNOWN_GAPS");
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
            for (Method m : type.getDeclaredMethods()) {
                String verb = verb(m);
                if (verb == null) {
                    continue;
                }
                Path sub = m.getAnnotation(Path.class);
                String path = "/" + trim(base.value()) + (sub == null ? "" : "/" + trim(sub.value()));
                found.add(verb + " " + path.replaceAll("/+", "/").replaceAll("\\{[^}/]+\\}", "01ARZ3NDEKTSV4RRFFQ69G5FAV"));
            }
        }
        return new ArrayList<>(found);
    }

    private static String trim(String s) {
        return s.replaceAll("^/+|/+$", "");
    }

    private static String verb(Method m) {
        if (m.isAnnotationPresent(GET.class)) return "GET";
        if (m.isAnnotationPresent(POST.class)) return "POST";
        if (m.isAnnotationPresent(PUT.class)) return "PUT";
        if (m.isAnnotationPresent(DELETE.class)) return "DELETE";
        if (m.isAnnotationPresent(PATCH.class)) return "PATCH";
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
                boolean testOnly = source != null && source.getLocation().getPath().contains("test-classes");
                if (!testOnly && c.isAnnotationPresent(Path.class)) {
                    out.add(c);
                }
            } catch (Throwable ignored) {
                // optional dependency not on the classpath: not a route of this build
            }
        }
        return out;
    }
}
