package io.github.bassemzohdy.openworkflow.agentic.testkit.fault;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Own a stopped proxy's port without listening. Only allocation is retried; behavioral assertions execute once. */
record StoppedProxyPort(FaultProxy proxy, Socket reservation) implements AutoCloseable {
    @FunctionalInterface interface Factory { FaultProxy start() throws IOException; }

    static StoppedProxyPort acquire(FaultProxy initial, Factory factory) throws IOException {
        FaultProxy candidate = initial;
        BindException last = null;
        for (int allocation = 0; allocation < 32; allocation++) {
            candidate.stop();
            Socket reservation = new Socket();
            try {
                // Exclusive non-listening bind. A TIME_WAIT or concurrent ephemeral allocation loses this attempt,
                // and gets a fresh proxy/port rather than sharing an address with another socket.
                reservation.setReuseAddress(false);
                reservation.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),candidate.port()));
                return new StoppedProxyPort(candidate,reservation);
            } catch (BindException occupied) {
                reservation.close(); last = occupied;
                if (allocation < 31) candidate = factory.start();
            } catch (IOException failure) {
                reservation.close(); throw failure;
            }
        }
        throw new IOException("could not acquire a stopped proxy port after 32 allocations",last);
    }
    @Override public void close() throws IOException { reservation.close(); }
}
