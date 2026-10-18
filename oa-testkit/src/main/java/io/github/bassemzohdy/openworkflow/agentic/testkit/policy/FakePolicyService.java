package io.github.bassemzohdy.openworkflow.agentic.testkit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A fake policy decision point with the shape of the OPA Data API: {@code POST /v1/data/<path>} with
 * {@code {"input": ...}} answers {@code {"result": <decision>}}.
 *
 * <p>Each request consumes the next scripted {@link Behavior}. When the script is empty the default behavior
 * applies, which is {@code deny}, so an unscripted policy call fails closed. Requests are recorded for assertions.
 */
public final class FakePolicyService implements AutoCloseable {

    /** How the service answers one request. */
    public sealed interface Behavior permits Decide, Fail, Stall {
    }

    /** Answers with a decision (true allows, false denies). */
    public record Decide(boolean allow) implements Behavior {
    }

    /** Answers with an HTTP error status and no decision. */
    public record Fail(int status) implements Behavior {
    }

    /** Waits {@code delay} before answering {@code allow}. A caller with a shorter timeout gives up first. */
    public record Stall(Duration delay) implements Behavior {
    }

    public static Behavior allow() {
        return new Decide(true);
    }

    public static Behavior deny() {
        return new Decide(false);
    }

    public static Behavior error(int status) {
        return new Fail(status);
    }

    public static Behavior timeoutAfter(Duration delay) {
        return new Stall(delay);
    }

    /** One request as received: the policy path and the {@code input} document. */
    public record Request(String path, JsonNode input) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Deque<Behavior> script = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();
    private Behavior defaultBehavior = deny();
    private HttpServer http;

    /** Starts the HTTP endpoint on 127.0.0.1 with a free port. */
    public synchronized FakePolicyService start() throws IOException {
        if (http != null) {
            throw new IllegalStateException("already started");
        }
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/v1/data/", this::handle);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-policy");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        return this;
    }

    /** Base URL, for example {@code http://127.0.0.1:41234}. */
    public synchronized String baseUrl() {
        if (http == null) {
            throw new IllegalStateException("start() the policy service first");
        }
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    /** Appends behaviors; they are consumed in order by the next requests. */
    public synchronized FakePolicyService enqueue(Behavior... behaviors) {
        for (Behavior b : behaviors) {
            script.add(b);
        }
        return this;
    }

    /** The behavior once the script is empty. Default: deny (fail closed). */
    public synchronized FakePolicyService defaultTo(Behavior behavior) {
        this.defaultBehavior = behavior;
        return this;
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized int remainingScript() {
        return script.size();
    }

    @Override
    public synchronized void close() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"code\":\"method_not_allowed\"}");
            return;
        }
        String path = ex.getRequestURI().getPath().substring("/v1/data/".length());
        JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
        Behavior behavior;
        synchronized (this) {
            requests.add(new Request(path, body.path("input")));
            behavior = script.isEmpty() ? defaultBehavior : script.poll();
        }
        if (behavior instanceof Fail e) {
            send(ex, e.status(), "{\"code\":\"internal_error\"}");
        } else if (behavior instanceof Stall s) {
            sleep(s.delay());
            send(ex, 200, decision(true));
        } else {
            send(ex, 200, decision(((Decide) behavior).allow()));
        }
    }

    private static String decision(boolean allow) throws IOException {
        ObjectNode out = JSON.createObjectNode();
        out.put("result", allow);
        return JSON.writeValueAsString(out);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
