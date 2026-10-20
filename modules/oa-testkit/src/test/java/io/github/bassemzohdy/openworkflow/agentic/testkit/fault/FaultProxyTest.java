package io.github.bassemzohdy.openworkflow.agentic.testkit.fault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the proxy against a local echo server. No database is involved. */
class FaultProxyTest {

    private ServerSocket echo;
    private FaultProxy proxy;

    @BeforeEach
    void startEchoAndProxy() throws IOException {
        echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!echo.isClosed()) {
                try {
                    Socket s = echo.accept();
                    Thread t = new Thread(() -> echoBack(s));
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "echo-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        proxy = new FaultProxy("127.0.0.1", echo.getLocalPort()).start();
    }

    @AfterEach
    void stopAll() throws IOException {
        proxy.stop();
        echo.close();
    }

    @Test
    void forwardsBytesBothWays() throws IOException {
        try (Socket c = connect()) {
            c.getOutputStream().write("hello".getBytes());
            assertArrayEquals("hello".getBytes(), readExactly(c, 5));
        }
    }

    @Test
    void pauseHoldsTrafficWithoutDroppingItAndResumeDeliversIt() throws IOException {
        try (Socket c = connect()) {
            c.setSoTimeout(300);
            proxy.pause();
            c.getOutputStream().write("held".getBytes());
            assertThrows(SocketTimeoutException.class, () -> c.getInputStream().read(new byte[4]));
            proxy.resume();
            c.setSoTimeout(5000);
            assertArrayEquals("held".getBytes(), readExactly(c, 4));
        }
    }

    @Test
    void delayAddsLatencyToEachForwardedChunk() throws IOException {
        proxy.setDelay(Duration.ofMillis(200));
        try (Socket c = connect()) {
            long start = System.nanoTime();
            c.getOutputStream().write("x".getBytes());
            assertArrayEquals("x".getBytes(), readExactly(c, 1));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsedMs >= 180, "round trip took only " + elapsedMs + " ms");
        }
    }

    @Test
    void cutConnectionsClosesOpenClientsButTheProxyKeepsListening() throws IOException {
        Socket c = connect();
        c.getOutputStream().write("a".getBytes());
        readExactly(c, 1);
        proxy.cutConnections();
        c.setSoTimeout(2000);
        assertEquals(-1, c.getInputStream().read(), "client should see end of stream after a cut");
        c.close();
        try (Socket fresh = connect()) {
            fresh.getOutputStream().write("b".getBytes());
            assertArrayEquals("b".getBytes(), readExactly(fresh, 1));
        }
    }

    @Test
    void stopRefusesNewConnectionsAndStartListensAgainOnTheSamePort() throws IOException {
        int port;
        try (var stopped = StoppedProxyPort.acquire(proxy,() -> new FaultProxy("127.0.0.1",echo.getLocalPort()).start())) {
            proxy = stopped.proxy(); port = proxy.port();
            assertEquals(false, proxy.isListening());
            assertThrows(ConnectException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());
        }
        proxy.start();
        assertEquals(port, proxy.port());
        try (Socket c = connect()) {
            c.getOutputStream().write("z".getBytes());
            assertArrayEquals("z".getBytes(), readExactly(c, 1));
        }
    }

    @Test
    void refusesToStartTwice() {
        assertThrows(IllegalStateException.class, () -> proxy.start());
    }

    @Test
    void connectionsFailWhenTheTargetIsDown() throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        try (FaultProxy toNothing = new FaultProxy("127.0.0.1", deadPort).start()) {
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), toNothing.port())) {
                c.setSoTimeout(3000);
                AtomicReference<Integer> read = new AtomicReference<>();
                read.set(c.getInputStream().read());
                assertEquals(-1, read.get(), "the proxy closes clients whose target is unreachable");
            }
        }
    }

    private Socket connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port()), 2000);
        s.setSoTimeout(5000);
        return s;
    }

    private static byte[] readExactly(Socket s, int n) throws IOException {
        byte[] out = new byte[n];
        InputStream in = s.getInputStream();
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) {
                throw new IOException("end of stream after " + off + " bytes");
            }
            off += r;
        }
        return out;
    }

    private static void echoBack(Socket s) {
        try (Socket sock = s; InputStream in = sock.getInputStream(); OutputStream out = sock.getOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // the client went away
        }
    }
}
