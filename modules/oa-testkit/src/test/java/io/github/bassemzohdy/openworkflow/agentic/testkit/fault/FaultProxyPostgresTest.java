package io.github.bassemzohdy.openworkflow.agentic.testkit.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.net.InetSocketAddress;
import java.net.BindException;
import java.net.Socket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Database outage through the proxy: JDBC talks to the local Postgres (localhost:5432, the same instance the other
 * tests use) through a FaultProxy. Pausing the proxy makes the query hang until the socket timeout.
 */
class FaultProxyPostgresTest {

    private FaultProxy proxy;

    @BeforeEach
    void start() throws Exception {
        proxy = new FaultProxy("127.0.0.1", 5432).start();
    }

    @AfterEach
    void stop() throws Exception {
        proxy.stop();
    }

    @Test
    void queriesWorkThroughTheProxyPauseMakesThemTimeOutAndResumeRestoresThem() throws Exception {
        try (Connection c = connect(proxy.port(), 5)) {
            assertEquals(1, scalar(c, "select 1"));
            proxy.pause();
            SQLException outage = assertThrows(SQLException.class, () -> scalar(c, "select 2"));
            assertTrue(outage.getMessage() != null, "a stalled database must surface as a SQL error");
        }
        proxy.resume();
        try (Connection c = connect(proxy.port(), 5)) {
            assertEquals(3, scalar(c, "select 3"));
        }
    }

    @Test
    void connectionsAreRefusedWhileTheProxyIsStopped() throws Exception {
        // Acquire the non-listening reservation before testing refusal. A kernel/client or another process may
        // acquire a released ephemeral port first; retry only that resource acquisition, never the SQL assertion.
        try (Socket reservation = reserveStoppedPort()) {
            assertThrows(SQLException.class, () -> {
                try (Connection unexpected = connect(proxy.port(), 2)) {
                    scalar(unexpected, "select 1");
                }
            });
        }
        proxy.start();
        try (Connection c = connect(proxy.port(), 5)) {
            assertEquals(4, scalar(c, "select 4"));
        }
    }

    private Socket reserveStoppedPort() throws Exception {
        BindException last = null;
        for (int allocation = 0; allocation < 32; allocation++) {
            proxy.stop();
            Socket reservation = new Socket();
            try {
                // Match the listener's reuse policy so old TCP TIME_WAIT records do not prevent acquisition.
                reservation.setReuseAddress(true);
                reservation.bind(new InetSocketAddress("127.0.0.1", proxy.port()));
                return reservation;
            } catch (BindException occupied) {
                reservation.close();
                last = occupied;
                if (allocation < 31) proxy = new FaultProxy("127.0.0.1", 5432).start();
            } catch (Exception failure) {
                reservation.close();
                throw failure;
            }
        }
        throw new BindException("could not reserve a stopped proxy port after 32 allocations: " + last);
    }

    private static Connection connect(int port, int socketTimeoutSeconds) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "postgres");
        p.setProperty("password", "postgres");
        p.setProperty("connectTimeout", "3");
        p.setProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/agentic", p);
    }

    private static int scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
