package spikeb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/** Database access for the spike. Uses database spike_b only; never the application database. */
public final class Db {

    public static final String JDBC_URL = "jdbc:postgresql://localhost:5432/spike_b";
    public static final String USER = "postgres";
    public static final String PASSWORD = "postgres";

    private Db() {}

    public static Connection admin() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    /** Runs a query that returns one numeric value (count, sum). Used for measurements and checks. */
    public static long queryLong(String sql) throws SQLException {
        try (Connection c = admin(); Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Drops and recreates a schema so each test starts from empty tables. */
    public static void resetSchema(String schema) throws SQLException {
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            st.execute("CREATE SCHEMA " + schema);
        }
    }

    /** Hikari pool bound to one schema of spike_b. */
    public static HikariDataSource pool(String schema, int maxPool) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(JDBC_URL);
        cfg.setUsername(USER);
        cfg.setPassword(PASSWORD);
        cfg.setSchema(schema);
        cfg.setMaximumPoolSize(maxPool);
        cfg.setPoolName("spike-" + schema);
        return new HikariDataSource(cfg);
    }

    /** Wraps a DataSource so connections, statements, rows and text read are counted. */
    public static DataSource counting(DataSource delegate, DbStats stats) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        Connection raw = (Connection) invoke(method, delegate, args);
                        stats.connectionsOpened.incrementAndGet();
                        int open = stats.openNow.incrementAndGet();
                        stats.peakOpen.accumulateAndGet(open, Math::max);
                        return wrapConnection(raw, stats);
                    }
                    return invoke(method, delegate, args);
                });
    }

    /** Counters filled by the counting wrapper. */
    public static final class DbStats {
        public final AtomicLong connectionsOpened = new AtomicLong();
        public final AtomicInteger openNow = new AtomicInteger();
        public final AtomicInteger peakOpen = new AtomicInteger();
        public final AtomicLong statements = new AtomicLong();
        public final AtomicLong updates = new AtomicLong();
        public final AtomicLong rowsRead = new AtomicLong();
        public final AtomicLong charsRead = new AtomicLong();

        public void reset() {
            connectionsOpened.set(0);
            peakOpen.set(openNow.get());
            statements.set(0);
            updates.set(0);
            rowsRead.set(0);
            charsRead.set(0);
        }

        @Override
        public String toString() {
            return "connectionsOpened=" + connectionsOpened.get() + " peakOpen=" + peakOpen.get()
                    + " statements=" + statements.get() + " updates=" + updates.get()
                    + " rowsRead=" + rowsRead.get() + " charsRead=" + charsRead.get();
        }
    }

    private static Connection wrapConnection(Connection raw, DbStats stats) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new InvocationHandler() {
                    private boolean closed;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        switch (method.getName()) {
                            case "close" -> {
                                if (!closed) {
                                    closed = true;
                                    stats.openNow.decrementAndGet();
                                }
                                return Db.invoke(method, raw, args);
                            }
                            case "prepareStatement" -> {
                                PreparedStatement ps = (PreparedStatement) Db.invoke(method, raw, args);
                                return wrapStatement(ps, PreparedStatement.class, stats);
                            }
                            case "createStatement" -> {
                                Statement st = (Statement) Db.invoke(method, raw, args);
                                return wrapStatement(st, Statement.class, stats);
                            }
                            default -> {
                                return Db.invoke(method, raw, args);
                            }
                        }
                    }
                });
    }

    private static Object wrapStatement(Object target, Class<?> iface, DbStats stats) {
        return Proxy.newProxyInstance(
                Db.class.getClassLoader(),
                new Class<?>[] {iface},
                (proxy, method, args) -> {
                    Object result = invoke(method, target, args);
                    switch (method.getName()) {
                        case "executeQuery" -> {
                            stats.statements.incrementAndGet();
                            return wrapResultSet((ResultSet) result, stats);
                        }
                        case "executeUpdate" -> {
                            stats.statements.incrementAndGet();
                            stats.updates.incrementAndGet();
                        }
                        case "execute" -> stats.statements.incrementAndGet();
                        default -> { }
                    }
                    return result;
                });
    }

    private static ResultSet wrapResultSet(ResultSet rs, DbStats stats) {
        return (ResultSet) Proxy.newProxyInstance(
                Db.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, rs, args);
                    if (method.getName().equals("next") && Boolean.TRUE.equals(result)) {
                        stats.rowsRead.incrementAndGet();
                    } else if (method.getName().equals("getString") && result instanceof String s) {
                        stats.charsRead.addAndGet(s.length());
                    }
                    return result;
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
