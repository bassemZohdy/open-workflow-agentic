package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import javax.sql.DataSource;

/**
 * Runs a unit of JDBC work in one transaction on one connection. A {@link SQLException} becomes a
 * {@link StoreUnavailableException}; a domain exception from the work (for example an illegal transition) is
 * rethrown unchanged after the rollback, so callers can tell the two apart.
 */
public final class Transactions {
    private static final Executor DIRECT = Runnable::run;

    /** A unit of JDBC work that runs on one connection inside one transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private Transactions() {
    }

    public static <T> T inTransaction(DataSource dataSource, Work<T> work) {
        return inTransaction(dataSource,work,0);
    }

    /** Adds a bounded client-side socket timeout, restoring the pooled connection before it is returned. */
    public static <T> T inTransaction(DataSource dataSource, Work<T> work, int networkTimeoutMillis) {
        try (Connection connection = dataSource.getConnection()) {
            int oldNetwork=0;boolean networkChanged=false;Throwable primary=null;
            try {
                if(networkTimeoutMillis>0) {
                    oldNetwork=connection.getNetworkTimeout();
                    int effective=oldNetwork>0?Math.min(oldNetwork,networkTimeoutMillis):networkTimeoutMillis;
                    connection.setNetworkTimeout(DIRECT,effective);networkChanged=true;
                }
                connection.setAutoCommit(false);
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                primary=e;
                rollback(connection);
                throw new StoreUnavailableException("the registry database failed", e);
            } catch (RuntimeException | Error e) {
                primary=e;
                rollback(connection);
                throw e;
            } finally {
                if(networkChanged)try {connection.setNetworkTimeout(DIRECT,oldNetwork);}
                catch(SQLException reset) {
                    if(primary!=null)primary.addSuppressed(reset);
                    else throw reset;
                }
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the registry database is not reachable", e);
        }
    }

    /** Runs short owned work outside an ambient JTA transaction and never loses the primary failure on resume. */
    public static <T> T inSuspendedTransaction(TransactionManager transactions,Supplier<T> work) {
        Objects.requireNonNull(work);
        if(transactions==null)return work.get();
        Transaction prior;
        try {prior=transactions.suspend();}
        catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be suspended",failed);}
        Throwable primary=null;
        try {return work.get();}
        catch(RuntimeException|Error failed){primary=failed;throw failed;}
        finally {if(prior!=null)try {transactions.resume(prior);}catch(Exception failed) {
            if(primary==null)throw new StoreUnavailableException("caller transaction cannot be resumed",failed);
            if(primary!=failed)primary.addSuppressed(failed);
        }}
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException suppressed) {
            // The original failure is the one the caller needs; a failed rollback leaves the connection to be closed.
        }
    }
}
