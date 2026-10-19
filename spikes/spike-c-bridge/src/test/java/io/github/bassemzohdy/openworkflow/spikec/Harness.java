package io.github.bassemzohdy.openworkflow.spikec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Process-level harness. Each application run is a separate JVM started from the packaged jar, killed with
 * SIGKILL ({@code kill -9}) and started again on the same database, as spike-a and spike-b do. Only the database
  * named by SPIKEC_JDBC_URL is used (default spike_c). The HTTP port is 18196; 8081 and 18195 belong to other sessions.
 */
final class Harness {

    static final String BASE = "http://127.0.0.1:18196";
    static final String JDBC = System.getenv().getOrDefault("SPIKEC_JDBC_URL", "jdbc:postgresql://localhost:5432/spike_c");
    static final Path JAR = Path.of("target/quarkus-app/quarkus-run.jar");
    static final Path LOGS = Path.of("target/process-logs");
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private static final List<App> STARTED = new CopyOnWriteArrayList<>();

    private Harness() {
    }

    /** Drops and recreates the schemas of spike_c (Flow tables live in public, spike tables in spikec). */
    static void resetDatabase() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS spikec CASCADE");
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
    }

    static Connection connection() throws SQLException {
        return DriverManager.getConnection(JDBC, "postgres", "postgres");
    }

    static long count(String sql, Object... args) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    static App start(String name, Map<String, String> props) throws IOException {
        App app = new App(name, props);
        app.start();
        STARTED.add(app);
        return app;
    }

    static void stopAll() {
        for (App app : STARTED) {
            app.stop();
        }
        STARTED.clear();
    }

    static Map<String, Object> post(String path, Object body) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) {
            throw new AssertionError("POST " + path + " answered " + r.statusCode() + ": " + r.body());
        }
        return r.body().isBlank() ? Map.of() : JSON.readValue(r.body(), new TypeReference<>() {
        });
    }

    /** GET as a map, or null when the answer is 404 or nothing answers. */
    static Map<String, Object> get(String path) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                return null;
            }
            if (r.statusCode() != 200) {
                return null;
            }
            return JSON.readValue(r.body(), new TypeReference<>() {
            });
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("timed out after " + timeout + " waiting for: " + what);
    }

    /** One application process: start, readiness, kill -9, restart. */
    static final class App {

        private final String name;
        private final Map<String, String> props;
        private final Path log;
        private Process process;

        App(String name, Map<String, String> props) {
            this.name = name;
            this.props = Map.copyOf(props);
            this.log = LOGS.resolve(name + ".log");
        }

        void start() throws IOException {
            if (!Files.exists(JAR)) {
                throw new IllegalStateException("missing " + JAR + "; run mvn package -DskipTests first");
            }
            Files.createDirectories(LOGS);
            List<String> cmd = new ArrayList<>();
            cmd.add(System.getProperty("java.home") + "/bin/java");
            cmd.add("-Dquarkus.http.port=18196");
            props.forEach((k, v) -> cmd.add("-D" + k + "=" + v));
            cmd.add("-jar");
            cmd.add(JAR.toString());
            process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(log.toFile())).start();
        }

        /** Ready means the reconciler has finished (the endpoint answers 200 only after that). */
        App awaitReady() throws InterruptedException {
            await(name + " ready", Duration.ofSeconds(120), () -> {
                if (!process.isAlive()) {
                    throw new AssertionError(name + " exited early; see " + log);
                }
                return get("/spikec/ready") != null;
            });
            return this;
        }

        /** kill -9 and wait for the process to be gone. */
        void kill9() throws IOException, InterruptedException {
            new ProcessBuilder("kill", "-9", Long.toString(process.pid())).start().waitFor();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError(name + " did not die after kill -9");
            }
        }

        void stop() {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        String logText() throws IOException {
            return Files.exists(log) ? Files.readString(log) : "";
        }
    }
}
