package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Runs a unit of JDBC work in one transaction on one connection. A {@link SQLException} becomes a
 * {@link StoreUnavailableException}; a domain exception from the work (for example an illegal transition) is
 * rethrown unchanged after the rollback, so callers can tell the two apart.
 */
public final class Transactions {

    /** A unit of JDBC work that runs on one connection inside one transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private Transactions() {
    }

    public static <T> T inTransaction(DataSource dataSource, Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                rollback(connection);
                throw new StoreUnavailableException("the registry database failed", e);
            } catch (RuntimeException | Error e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the registry database is not reachable", e);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException suppressed) {
            // The original failure is the one the caller needs; a failed rollback leaves the connection to be closed.
        }
    }
}
