package io.github.bassemzohdy.openworkflow.agentic.testkit.process;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An application started as its own operating system process, for fault injection.
 *
 * <p>The process is started with {@code -Dquarkus.http.port} set to a port that stays the same across restarts, so
 * a test can kill it with {@link #kill()} (SIGKILL on Linux and macOS), start it again with {@link #restart()}
 * and reach the same URL. Output goes to a log file. No container runtime is needed.
 *
 * <p>Typical use: {@code ManagedProcess.jar(path).property("x", "y").build()}, then {@code start()},
 * {@code awaitReady(...)}, {@code kill()}, {@code restart()}, {@code awaitReady(...)}.
 */
public final class ManagedProcess implements AutoCloseable {

    private final List<String> command;
    private final int port;
    private final String readinessPath;
    private final Path logFile;
    private final Path workingDirectory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Process process;

    private ManagedProcess(List<String> command, int port, String readinessPath, Path logFile, Path workingDirectory) {
        this.command = List.copyOf(command);
        this.port = port;
        this.readinessPath = readinessPath;
        this.logFile = logFile;
        this.workingDirectory = workingDirectory;
    }

    /** Starts from an executable jar (for example a Quarkus {@code quarkus-run.jar}). */
    public static Builder jar(Path jar) {
        return new Builder(jar, null, null);
    }

    /** Starts a main class from the given classpath. */
    public static Builder mainClass(String classpath, String mainClass) {
        return new Builder(null, classpath, mainClass);
    }

    /** The port the process listens on; kept across restarts. */
    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public Path logFile() {
        return logFile;
    }

    /** The process id, or -1 if the process was never started. */
    public long pid() {
        return process == null ? -1 : process.pid();
    }

    public synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Starts the process. Fails if it is already running. */
    public synchronized void start() throws IOException {
        if (isAlive()) {
            throw new IllegalStateException("process " + pid() + " is already running");
        }
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }
        process = pb.start();
    }

    /**
     * Polls the readiness path until it answers 200, the process exits, or the timeout passes.
     *
     * @return true when the process became ready in time
     */
    public boolean awaitReady(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!isAlive()) {
                return false;
            }
            if (httpStatus(readinessPath) == 200) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /** The HTTP status of a GET on this process, or -1 when nothing answers (for example after a kill). */
    public int httpStatus(String path) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Kills the process with SIGKILL (Java's {@code destroyForcibly}) and waits for it to exit. */
    public synchronized void kill() throws InterruptedException {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Kills the process if it runs, then starts it again on the same port with the same configuration. */
    public synchronized void restart() throws IOException, InterruptedException {
        kill();
        start();
    }

    @Override
    public void close() throws InterruptedException {
        kill();
    }

    /** Chooses a free TCP port. There is a small race between choosing and binding; tests accept it. */
    public static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    public static final class Builder {
        private final Path jar;
        private final String classpath;
        private final String mainClass;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final List<String> jvmArgs = new ArrayList<>();
        private final List<String> args = new ArrayList<>();
        private Integer port;
        private String readinessPath = "/q/health/ready";
        private Path logFile;
        private Path workingDirectory;
        private String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        private Builder(Path jar, String classpath, String mainClass) {
            this.jar = jar;
            this.classpath = classpath;
            this.mainClass = mainClass;
        }

        /** Adds a system property ({@code -Dkey=value}). */
        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        /** Sets {@code quarkus.http.port}; if not set, a free port is chosen at build time. */
        public Builder httpPort(int port) {
            this.port = port;
            return this;
        }

        /** The path polled by {@link #awaitReady}. Default {@code /q/health/ready}. */
        public Builder readinessPath(String path) {
            this.readinessPath = path;
            return this;
        }

        public Builder logFile(Path file) {
            this.logFile = file;
            return this;
        }

        public Builder workingDirectory(Path dir) {
            this.workingDirectory = dir;
            return this;
        }

        /** Extra JVM option, for example {@code -Xmx256m}. */
        public Builder jvmArg(String arg) {
            jvmArgs.add(arg);
            return this;
        }

        /** Program argument, after the jar or the main class. */
        public Builder arg(String arg) {
            args.add(arg);
            return this;
        }

        /** The java executable. Default: the one running the tests. */
        public Builder javaExecutable(Path javaExecutable) {
            this.java = javaExecutable.toString();
            return this;
        }

        public ManagedProcess build() throws IOException {
            int actualPort = port != null ? port : freePort();
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            cmd.addAll(jvmArgs);
            cmd.add("-Dquarkus.http.port=" + actualPort);
            properties.forEach((k, v) -> cmd.add("-D" + k + "=" + v));
            if (jar != null) {
                cmd.add("-jar");
                cmd.add(jar.toAbsolutePath().toString());
            } else {
                cmd.add("-cp");
                cmd.add(classpath);
                cmd.add(mainClass);
            }
            cmd.addAll(args);
            Path log = logFile != null ? logFile : Files.createTempFile("managed-process-", ".log");
            return new ManagedProcess(cmd, actualPort, readinessPath, log, workingDirectory);
        }
    }
}
