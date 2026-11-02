package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.*;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only process: commit one decision and stop abruptly before reporting a result. */
public final class HumanDecisionCrashWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].matches("owa_human_[0-9a-f]{32}")) throw new IllegalArgumentException();
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setDatabaseName(args[0]);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        var decision = new HumanDecision(Ulid.parse(args[3]), "tenant", Ulid.parse(args[1]), 0,
                new Subject(SubjectKind.USER, "alice"), HumanDecision.Outcome.APPROVE, "private");
        var journal = new HumanDecisionJournal(Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC));
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            if (journal.record(c, decision, Ulid.parse(args[2]), "wait") != HumanDecisionJournal.Result.RECORDED) {
                throw new IllegalStateException("expected a new decision");
            }
            c.commit();
            Runtime.getRuntime().halt(77);
        }
    }
}
