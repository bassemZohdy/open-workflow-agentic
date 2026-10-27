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

    /** Runs a bounded READ COMMITTED PostgreSQL unit of work with transaction-local timeouts. */
    public static <T> T inBoundedPostgresTransaction(DataSource dataSource,Work<T> work,
            int lockTimeoutMillis,int statementTimeoutMillis,int idleTimeoutMillis) {
        if(lockTimeoutMillis<1||statementTimeoutMillis<lockTimeoutMillis||idleTimeoutMillis<1)
            throw new IllegalArgumentException("PostgreSQL transaction timeouts must be positive and ordered");
        return inTransaction(dataSource,c->{
            try(var s=c.createStatement()){s.execute("set transaction isolation level read committed");}
            capPostgresTimeouts(c,lockTimeoutMillis,statementTimeoutMillis,idleTimeoutMillis);
            return work.run(c);
        });
    }

    /** Adds a bounded client-side socket timeout, restoring the pooled connection before it is returned. */
    public static <T> T inTransaction(DataSource dataSource, Work<T> work, int networkTimeoutMillis) {
        try (Connection connection = dataSource.getConnection()) {
            int oldNetwork=0;boolean networkChanged=false,committed=false;Throwable primary=null;
            try {
                if(networkTimeoutMillis>0) {
                    oldNetwork=setClientNetworkTimeout(connection,networkTimeoutMillis);networkChanged=true;
                }
                connection.setAutoCommit(false);
                T result = work.run(connection);
                connection.commit();
                committed=true;
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
                if(networkChanged)try {restoreClientNetworkTimeout(connection,oldNetwork);}
                catch(SQLException reset) {
                    try {abort(connection);}catch(SQLException abort){reset.addSuppressed(abort);}
                    if(primary!=null)primary.addSuppressed(reset);
                    else if(!committed)throw reset;
                    // If committed, discard the bad pooled connection without changing its durable result.
                }
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("the registry database is not reachable", e);
        }
    }

    static int setClientNetworkTimeout(Connection connection,int requestedMillis) throws SQLException {
        int old=connection.getNetworkTimeout();
        int effective=old>0?Math.min(old,requestedMillis):requestedMillis;
        connection.setNetworkTimeout(DIRECT,effective);
        return old;
    }

    static void restoreClientNetworkTimeout(Connection connection,int previousMillis) throws SQLException {
        connection.setNetworkTimeout(DIRECT,previousMillis);
    }

    static void abort(Connection connection) throws SQLException { connection.abort(DIRECT); }

    static void capPostgresTimeouts(Connection connection,int lockMillis,int statementMillis,int idleMillis) throws SQLException {
        String cap="pg_catalog.set_config(?,least(coalesce(nullif(extract(epoch from "
                +"pg_catalog.current_setting(?)::pg_catalog.interval)*1000,0),?),?)::bigint::text||'ms',true)";
        try(var q=connection.prepareStatement("select "+cap+","+cap+","+cap)) {
            q.setQueryTimeout((statementMillis+999)/1000);
            String[] names={"lock_timeout","statement_timeout","idle_in_transaction_session_timeout"};
            long[] values={lockMillis,statementMillis,idleMillis};
            for(int i=0;i<names.length;i++) {
                q.setString(i*4+1,names[i]);q.setString(i*4+2,names[i]);
                q.setLong(i*4+3,values[i]);q.setLong(i*4+4,values[i]);
            }
            q.execute();
        }
    }

    public static String beginReadOnlySnapshot(Connection connection,int queryTimeoutSeconds) throws SQLException {
        try(var s=connection.createStatement()) {
            s.setQueryTimeout(queryTimeoutSeconds);
            String isolation;
            try(var r=s.executeQuery("show transaction_isolation")){r.next();isolation=r.getString(1);}
            s.execute(isolation.equals("serializable")?"set transaction read only":
                    "set transaction isolation level repeatable read read only");
            return isolation;
        }
    }

    /** Runs short owned work outside an ambient JTA transaction and never loses the primary failure on resume. */
    public static <T> T inSuspendedTransaction(TransactionManager transactions,Supplier<T> work) {
        Objects.requireNonNull(transactions);
        Objects.requireNonNull(work);
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
