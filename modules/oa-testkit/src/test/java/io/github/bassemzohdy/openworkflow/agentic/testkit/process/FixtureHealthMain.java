package io.github.bassemzohdy.openworkflow.agentic.testkit.process;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for an application: serves /q/health/ready on the port given by -Dquarkus.http.port.
 * Started by ManagedProcessTest as a child JVM. The name does not match surefire's includes.
 */
public final class FixtureHealthMain {

    private FixtureHealthMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("quarkus.http.port", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/q/health/ready", ex -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("fixture ready pid=" + ProcessHandle.current().pid());
        Thread.currentThread().join();
    }
}
