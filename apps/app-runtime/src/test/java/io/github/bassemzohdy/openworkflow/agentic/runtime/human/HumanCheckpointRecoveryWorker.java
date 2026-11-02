package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.CheckpointRecoveryAudit;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.JdbcInboxCheckpointRecovery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import java.time.Clock;
import org.postgresql.ds.PGSimpleDataSource;

/** Separate process recovering a checkpoint in the isolated human-checkpoint test database. */
public final class HumanCheckpointRecoveryWorker {
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[2].equals("halt") || args[2].equals("repeat") || args[2].equals("before-ack")
                || args[2].equals("owned-before-ack"))) throw new IllegalArgumentException();
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("HUMAN_CHECKPOINT_TEST_URL"));
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        try (var c = ds.getConnection(); var q = c.prepareStatement("select current_database()"); var rs = q.executeQuery()) {
            if (!rs.next() || !rs.getString(1).matches("owa_hcheckpoint_[0-9a-f]{32}"))
                throw new IllegalStateException("recovery worker requires an isolated test database");
        }
        var row = new JdbcInstanceStore(ds).find("tenant", Ulid.parse(args[0])).orElseThrow();
        if(args[2].equals("owned-before-ack")) {
            if(args.length!=8)throw new IllegalArgumentException();
            var wait=new InboxWait(row.tenant(),row.id(),args[1],row.engineInstanceId(),row.definition().namespace(),
                    row.definition().name(),row.definition().version(),args[3],Integer.parseInt(args[4]),args[5],args[6],args[7],java.time.Duration.ofMinutes(2));
            var clock=Clock.systemUTC();var trail=new AuditTrail(ds);var leases=new JdbcInboxTaskLease(ds,trail,clock);
            var owned=new JdbcOwnedInbox(ds,leases,new JdbcEventInbox(ds),new JdbcInboxCheckpointProof(ds),trail,clock,event->{
                CheckpointRecoveryAudit.record(ds,event);Runtime.getRuntime().halt(79);
            });
            owned.acknowledge(wait);throw new IllegalStateException("owned audit was not reached");
        }
        if(args.length!=3)throw new IllegalArgumentException();
        var recovery = new JdbcInboxCheckpointRecovery(ds, (connection, event) -> {
            CheckpointRecoveryAudit.append(connection,event);
            if (args[2].equals("before-ack")) Runtime.getRuntime().halt(78);
        }, Clock.systemUTC());
        if (!recovery.repairBatch(row, args[1], 16))
            throw new IllegalStateException("checkpoint recovery did not finish");
        if (args[2].equals("halt")) Runtime.getRuntime().halt(77);
    }
}
