package spikeb.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A separate JVM running spikeb.Worker. Output is read on a background thread. */
public final class ChildProcess {

    private final Process process;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> all = Collections.synchronizedList(new ArrayList<>());

    private ChildProcess(Process process) {
        this.process = process;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    all.add(line);
                    lines.add(line);
                }
            } catch (IOException e) {
                lines.add("IO-ERROR " + e.getMessage());
            }
        }, "child-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Starts spikeb.Worker with the given arguments on the test classpath. */
    public static ChildProcess worker(String... args) throws IOException {
        return start(System.getProperty("java.class.path"), "spikeb.Worker", args);
    }

    /** Starts a main class on an explicit classpath (used for evolved state classes). */
    public static ChildProcess start(String classpath, String mainClass, String... args) throws IOException {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        return new ChildProcess(p);
    }

    /** Waits until a line starting with prefix appears, and returns it. */
    public String awaitLine(String prefix, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith(prefix)) {
                return line;
            }
            if (!process.isAlive() && lines.isEmpty()) {
                break;
            }
        }
        throw new IllegalStateException("no line starting with '" + prefix + "' within " + timeout
                + "; output so far: " + all);
    }

    /** Sends SIGKILL (kill -9) to the child and waits for it to die. */
    public void kill9() throws IOException, InterruptedException {
        long pid = process.pid();
        new ProcessBuilder("kill", "-9", Long.toString(pid)).start().waitFor();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    /** Waits for a normal exit and returns all output lines. */
    public List<String> finish(Duration timeout) throws InterruptedException {
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("child did not exit; output: " + all);
        }
        Thread.sleep(100);
        return List.copyOf(all);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    public List<String> output() {
        return List.copyOf(all);
    }

    public static List<String> linesWith(List<String> output, String prefix) {
        return output.stream().filter(l -> l.startsWith(prefix)).toList();
    }
}
