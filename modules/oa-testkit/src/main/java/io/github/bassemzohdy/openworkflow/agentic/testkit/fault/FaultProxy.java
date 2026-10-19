package io.github.bassemzohdy.openworkflow.agentic.testkit.fault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-process TCP proxy that sits between a client and a server (for example JDBC to Postgres) and injects faults.
 *
 * <ul>
 *   <li>{@link #pause()} holds all bytes in both directions without dropping them. The connections stay open and
 *       the client sees silence, as with a stalled network. {@link #resume()} delivers them.</li>
 *   <li>{@link #setDelay(Duration)} adds latency before each forwarded chunk.</li>
 *   <li>{@link #cutConnections()} closes every open connection. New connections still work.</li>
 *   <li>{@link #stop()} closes the listening socket too, so new connections are refused (an outage).
 *       {@link #start()} listens again on the same port.</li>
 * </ul>
 *
 * <p>No container runtime is needed. The proxy is TCP-level and knows nothing about the protocol.
 * Thread-safe for use from one controlling thread plus the proxy's own threads.
 */
public final class FaultProxy implements AutoCloseable {

    private final String targetHost;
    private final int targetPort;
    private final Object gate = new Object();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean paused;
    private volatile Duration delay = Duration.ZERO;
    private volatile ServerSocket server;
    private volatile int port;
    private volatile boolean stopping;

    public FaultProxy(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /** Starts listening on 127.0.0.1. The first start picks a free port; later starts reuse it. */
    public synchronized FaultProxy start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("proxy is already listening on port " + port);
        }
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        server = s;
        port = s.getLocalPort();
        stopping = false;
        Thread acceptor = new Thread(() -> acceptLoop(s), "fault-proxy-accept-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
        return this;
    }

    /** The port the proxy listens on. Valid after {@link #start()}. */
    public int port() {
        return port;
    }

    public boolean isListening() {
        return server != null;
    }

    /** Holds traffic in both directions. */
    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (gate) {
            paused = false;
            gate.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /** Latency added before each forwarded chunk. Zero by default. */
    public void setDelay(Duration delay) {
        this.delay = delay == null ? Duration.ZERO : delay;
    }

    /** Closes every open connection. The proxy keeps listening. */
    public void cutConnections() {
        for (Connection c : connections) {
            c.close();
        }
        connections.clear();
    }

    /** Stops listening and closes open connections: new connections are refused until {@link #start()}. */
    public synchronized void stop() throws IOException {
        stopping = true;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            s.close();
        }
        cutConnections();
        resume();
    }

    public int openConnections() {
        return connections.size();
    }

    @Override
    public void close() throws IOException {
        stop();
    }

    private void acceptLoop(ServerSocket s) {
        while (!stopping && !s.isClosed()) {
            try {
                Socket client = s.accept();
                Socket upstream = new Socket();
                try {
                    upstream.connect(new InetSocketAddress(targetHost, targetPort), 2000);
                } catch (IOException e) {
                    client.close();
                    continue;
                }
                Connection c = new Connection(client, upstream);
                connections.add(c);
                c.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** One client connection with a pump in each direction. */
    private final class Connection {
        private final Socket client;
        private final Socket upstream;

        Connection(Socket client, Socket upstream) {
            this.client = client;
            this.upstream = upstream;
        }

        void start() {
            pump("client-to-server", client, upstream);
            pump("server-to-client", upstream, client);
        }

        private void pump(String name, Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        awaitNotPaused();
                        sleep(delay);
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    close();
                }
            }, "fault-proxy-" + name + "-" + port);
            t.setDaemon(true);
            t.start();
        }

        void close() {
            connections.remove(this);
            closeQuietly(client);
            closeQuietly(upstream);
        }

        private void awaitNotPaused() throws InterruptedException {
            synchronized (gate) {
                while (paused && !stopping) {
                    gate.wait();
                }
            }
        }
    }

    private static void sleep(Duration d) throws InterruptedException {
        if (!d.isZero() && !d.isNegative()) {
            Thread.sleep(d.toMillis(), (int) (d.toNanosPart() % 1_000_000));
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (SocketException ignored) {
            // already closed
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
    }
}
