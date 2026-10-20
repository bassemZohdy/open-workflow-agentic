package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import java.sql.Connection;
import javax.sql.DataSource;

/** Owned short PostgreSQL transactions only. Limits are per statement/lock, not a whole operation or network timeout. */
final class EffectStoreTransactions {
    private EffectStoreTransactions() { }
    static <T> T readCommitted(DataSource data,Transactions.Work<T> work) {return run(data,false,work);}
    static <T> T snapshot(DataSource data,Transactions.Work<T> work) {return run(data,true,work);}
    private static <T> T run(DataSource data,boolean snapshot,Transactions.Work<T> work) {
        return Transactions.inTransaction(data,c->{
            c.setTransactionIsolation(snapshot?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_READ_COMMITTED);
            // SET LOCAL is restored on commit/rollback; never change the DataSource, server or another session.
            try(var limits=c.createStatement()) {
                limits.execute("set local lock_timeout='2s'; set local statement_timeout='5s'");
            }
            return work.run(c);
        });
    }
}
