package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.CheckpointRecoveryAudit;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.JdbcInboxCheckpointRecovery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import java.time.Clock;
import org.postgresql.ds.PGSimpleDataSource;

/** Separate process recovering a checkpoint in the isolated human-checkpoint test database. */
public final class HumanCheckpointRecoveryWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[2].equals("halt") || args[2].equals("repeat") || args[2].equals("before-ack"))) throw new IllegalArgumentException();
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("HUMAN_CHECKPOINT_TEST_URL"));
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        try (var c = ds.getConnection(); var q = c.prepareStatement("select current_database()"); var rs = q.executeQuery()) {
            if (!rs.next() || !rs.getString(1).matches("owa_hcheckpoint_[0-9a-f]{32}"))
                throw new IllegalStateException("recovery worker requires an isolated test database");
        }
        var row = new JdbcInstanceStore(ds).find("tenant", Ulid.parse(args[0])).orElseThrow();
        var recovery = new JdbcInboxCheckpointRecovery(ds, (connection, event) -> {
            CheckpointRecoveryAudit.append(connection,event);
            if (args[2].equals("before-ack")) Runtime.getRuntime().halt(78);
        }, Clock.systemUTC());
        if (!recovery.repairBatch(row, args[1], 16))
            throw new IllegalStateException("checkpoint recovery did not finish");
        if (args[2].equals("halt")) Runtime.getRuntime().halt(77);
    }
}
