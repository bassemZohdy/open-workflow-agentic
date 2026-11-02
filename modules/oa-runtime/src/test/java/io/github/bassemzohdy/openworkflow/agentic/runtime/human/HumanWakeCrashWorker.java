package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import java.time.*;
import java.util.*;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only writer exits abruptly immediately after committing a staged human wake. */
public final class HumanWakeCrashWorker {
    public static void main(String[] args) {
        if (args.length != 5 || !args[0].matches("owa_hwait_[0-9a-f]{32}")) throw new IllegalArgumentException();
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(args[0]);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        var taskId = Ulid.parse(args[1]); var instance = Ulid.parse(args[2]);
        var decision = new HumanDecision(Ulid.parse(args[3]), "tenant", taskId, 0,
                new Subject(SubjectKind.USER, "alice"), HumanDecision.Outcome.APPROVE, "private");
        String filter = "{\"with\":{\"source\":\"" + HumanDecisionWake.SOURCE + "\",\"type\":\"" + HumanDecisionWake.TYPE + "\"}}";
        String snapshot = "{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"one\":" + filter + "}}}}";
        String hash = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.event-filter.v1").text(filter).toBytes());
        var wait = new InboxWait("tenant", instance, "app", args[4], "agentic", "human-test", "1", "do/0/human", 1,
                HumanDecisionWake.route(taskId), HumanDecisionWake.TYPE, hash, Duration.ofMinutes(2));
        var inbox = new JdbcEventInbox(ds); var clock = Clock.systemUTC();
        var leases = new JdbcInboxTaskLease(ds, event -> {}, clock);
        var matcher = new JdbcOwnedInbox(ds, leases, inbox, new JdbcInboxCheckpointProof(ds), event -> {}, clock);
        var claim = leases.acquire(wait.task(), "crash-owner", Duration.ofSeconds(10)).orElseThrow().claim();
        var group = new InboxListenGroup(wait.task(), InboxListenGroup.Strategy.ONE, List.of(hash), InboxListenGroup.Stop.NONE,
                List.of(), null, InboxListenGroup.snapshotFingerprint(snapshot));
        if (new HumanWakeStage(ds, inbox, matcher, clock).stage(decision, wait, claim, group, snapshot) != HumanWakeStage.Result.STAGED)
            throw new IllegalStateException("expected a new stage");
        Runtime.getRuntime().halt(77);
    }
}
